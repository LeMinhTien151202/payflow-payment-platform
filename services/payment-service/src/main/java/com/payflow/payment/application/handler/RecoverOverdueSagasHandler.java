package com.payflow.payment.application.handler;

import com.payflow.events.PayFlowTopics;
import com.payflow.events.account.AccountCaptureRequestedData;
import com.payflow.events.account.AccountEvents;
import com.payflow.events.account.AccountFundsReservedData;
import com.payflow.events.account.AccountReleaseRequestedData;
import com.payflow.events.account.AccountReserveRequestedData;
import com.payflow.events.ledger.LedgerEvents;
import com.payflow.events.ledger.LedgerPostPaymentRequestedData;
import com.payflow.events.payment.PaymentCreatedData;
import com.payflow.events.payment.PaymentEvents;
import com.payflow.payment.application.exception.ConcurrentSagaUpdateException;
import com.payflow.payment.application.exception.SagaRecoveryDataException;
import com.payflow.payment.application.port.OutboxAppender;
import com.payflow.payment.application.port.PaymentSagaStore;
import com.payflow.payment.application.port.PaymentWorkflowStore;
import com.payflow.payment.application.saga.PaymentSagaRecoveryPolicy;
import com.payflow.payment.application.saga.SagaRecoveryAction;
import com.payflow.payment.application.saga.SagaRecoveryBatchResult;
import com.payflow.payment.application.saga.SagaRecoverySettings;
import com.payflow.payment.application.saga.VersionedPayment;
import com.payflow.payment.application.saga.VersionedPaymentSaga;
import com.payflow.payment.domain.model.Payment;
import com.payflow.payment.domain.model.PaymentSaga;
import com.payflow.payment.domain.model.PaymentSagaStep;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Advances overdue Saga work in bounded, independent local transactions. */
@Service
public class RecoverOverdueSagasHandler {

    private final PaymentSagaStore sagas;
    private final PaymentWorkflowStore payments;
    private final PaymentSagaRecoveryPolicy policy;
    private final OutboxAppender outbox;
    private final SagaRecoverySettings settings;
    private final Clock clock;
    private final TransactionTemplate transactions;

    public RecoverOverdueSagasHandler(
            PaymentSagaStore sagas,
            PaymentWorkflowStore payments,
            PaymentSagaRecoveryPolicy policy,
            OutboxAppender outbox,
            SagaRecoverySettings settings,
            Clock clock,
            TransactionTemplate transactions) {
        this.sagas = sagas;
        this.payments = payments;
        this.policy = policy;
        this.outbox = outbox;
        this.settings = settings;
        this.clock = clock;
        this.transactions = transactions;
    }

    public SagaRecoveryBatchResult recoverDue() {
        Instant now = clock.instant();
        List<UUID> candidates = sagas.findDueIds(now, settings.batchSize());
        if (candidates.isEmpty()) {
            return SagaRecoveryBatchResult.empty();
        }

        int retried = 0;
        int compensating = 0;
        int manualReview = 0;
        int noAction = 0;
        int concurrent = 0;

        for (UUID sagaId : candidates) {
            try {
                RecoveryOutcome outcome = transactions.execute(status -> recoverOne(sagaId, now));
                switch (outcome) {
                    case RETRIED -> retried++;
                    case COMPENSATING -> compensating++;
                    case MANUAL_REVIEW -> manualReview++;
                    case NO_ACTION -> noAction++;
                }
            } catch (ConcurrentSagaUpdateException lostRace) {
                concurrent++;
            }
        }

        return new SagaRecoveryBatchResult(
                candidates.size(), retried, compensating, manualReview, noAction, concurrent);
    }

    private RecoveryOutcome recoverOne(UUID sagaId, Instant now) {
        VersionedPaymentSaga storedSaga = sagas.find(sagaId)
                .orElseThrow(() -> new SagaRecoveryDataException("PaymentSaga", sagaId));
        PaymentSaga saga = storedSaga.saga();
        VersionedPayment storedPayment = payments.findForWorkflow(saga.paymentId())
                .orElseThrow(() -> new SagaRecoveryDataException("Payment", saga.paymentId()));
        Payment payment = storedPayment.payment();
        Instant nextDeadline = now.plus(settings.stepTimeout());

        SagaRecoveryAction action = policy.onDeadline(
                payment,
                saga,
                reservationFact(payment, saga),
                now,
                nextDeadline,
                settings.maxRetries());
        if (action instanceof SagaRecoveryAction.NoAction) {
            return RecoveryOutcome.NO_ACTION;
        }

        sagas.update(storedSaga);
        if (!payment.recordedStatusChanges().isEmpty()) {
            payments.updateWorkflow(storedPayment);
        }
        appendAction(action, payment, saga, now);

        if (action instanceof SagaRecoveryAction.RetryStep) {
            return RecoveryOutcome.RETRIED;
        }
        if (action instanceof SagaRecoveryAction.ReleaseFunds) {
            return RecoveryOutcome.COMPENSATING;
        }
        return RecoveryOutcome.MANUAL_REVIEW;
    }

    private void appendAction(
            SagaRecoveryAction action, Payment payment, PaymentSaga saga, Instant occurredAt) {
        if (action instanceof SagaRecoveryAction.RetryStep retry) {
            appendRetry(retry.step(), payment, saga, occurredAt);
        } else if (action instanceof SagaRecoveryAction.ReleaseFunds release) {
            outbox.append(
                    AccountEvents.RELEASE_REQUESTED,
                    PayFlowTopics.PAYMENT_EVENTS,
                    payment.id().toString(),
                    occurredAt,
                    release.command());
        } else if (action instanceof SagaRecoveryAction.ManualReview manualReview) {
            outbox.append(
                    PaymentEvents.MANUAL_REVIEW_REQUIRED,
                    PayFlowTopics.PAYMENT_EVENTS,
                    payment.id().toString(),
                    occurredAt,
                    manualReview.eventData());
        }
    }

    private void appendRetry(
            PaymentSagaStep step, Payment payment, PaymentSaga saga, Instant occurredAt) {
        switch (step) {
            case RISK_ASSESSMENT -> outbox.append(
                    PaymentEvents.PAYMENT_CREATED,
                    PayFlowTopics.PAYMENT_EVENTS,
                    payment.id().toString(),
                    occurredAt,
                    new PaymentCreatedData(
                            payment.id(),
                            payment.merchantId(),
                            payment.customerId(),
                            payment.sourceAccountId(),
                            payment.amount().amount(),
                            payment.amount().currency(),
                            payment.createdAt()));
            case RESERVE_FUNDS -> outbox.append(
                    AccountEvents.RESERVE_REQUESTED,
                    PayFlowTopics.PAYMENT_EVENTS,
                    payment.id().toString(),
                    occurredAt,
                    new AccountReserveRequestedData(
                            payment.id(),
                            payment.sourceAccountId(),
                            payment.amount().amount(),
                            payment.amount().currency(),
                            saga.deadlineAt()));
            case POST_LEDGER -> outbox.append(
                    LedgerEvents.POST_PAYMENT_REQUESTED,
                    PayFlowTopics.PAYMENT_EVENTS,
                    payment.id().toString(),
                    occurredAt,
                    new LedgerPostPaymentRequestedData(
                            payment.id(),
                            payment.customerId(),
                            payment.merchantId(),
                            payment.amount().amount(),
                            payment.amount().currency()));
            case CAPTURE_FUNDS -> outbox.append(
                    AccountEvents.CAPTURE_REQUESTED,
                    PayFlowTopics.PAYMENT_EVENTS,
                    payment.id().toString(),
                    occurredAt,
                    new AccountCaptureRequestedData(
                            payment.id(),
                            payment.sourceAccountId(),
                            requiredReservationId(saga),
                            payment.amount().amount(),
                            payment.amount().currency()));
            case RELEASE_FUNDS -> outbox.append(
                    AccountEvents.RELEASE_REQUESTED,
                    PayFlowTopics.PAYMENT_EVENTS,
                    payment.id().toString(),
                    occurredAt,
                    new AccountReleaseRequestedData(
                            payment.id(),
                            payment.sourceAccountId(),
                            requiredReservationId(saga),
                            payment.amount().amount(),
                            payment.amount().currency(),
                            saga.lastErrorCode()));
            case COMPLETED -> throw new IllegalStateException("completed Saga cannot be retried");
        }
    }

    private static AccountFundsReservedData reservationFact(
            Payment payment, PaymentSaga saga) {
        return saga.reservationId() == null
                ? null
                : new AccountFundsReservedData(
                        payment.id(),
                        payment.sourceAccountId(),
                        saga.reservationId(),
                        payment.amount().amount(),
                        payment.amount().currency());
    }

    private static UUID requiredReservationId(PaymentSaga saga) {
        if (saga.reservationId() == null) {
            throw new SagaRecoveryDataException("Reservation", saga.id());
        }
        return saga.reservationId();
    }

    private enum RecoveryOutcome {
        RETRIED,
        COMPENSATING,
        MANUAL_REVIEW,
        NO_ACTION
    }
}
