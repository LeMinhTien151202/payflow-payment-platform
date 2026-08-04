package com.payflow.payment.application.handler;

import com.payflow.events.PayFlowTopics;
import com.payflow.events.account.AccountCaptureRequestedData;
import com.payflow.events.account.AccountEvents;
import com.payflow.events.account.AccountReleaseRequestedData;
import com.payflow.events.account.AccountReserveRequestedData;
import com.payflow.events.ledger.LedgerEvents;
import com.payflow.events.ledger.LedgerPostPaymentRequestedData;
import com.payflow.events.payment.PaymentEvents;
import com.payflow.events.payment.PaymentFailedData;
import com.payflow.payment.application.audit.AuditRecord;
import com.payflow.payment.application.audit.PaymentReviewAuditFacts;
import com.payflow.payment.application.exception.ManualReviewResolutionRejectedException;
import com.payflow.payment.application.exception.ManualReviewItemNotFoundException;
import com.payflow.payment.application.operations.ManualReviewDecision;
import com.payflow.payment.application.operations.ManualReviewResolutionResult;
import com.payflow.payment.application.operations.ResolveManualReviewCommand;
import com.payflow.payment.application.port.AuditLogAppender;
import com.payflow.payment.application.port.OutboxAppender;
import com.payflow.payment.application.port.PaymentSagaStore;
import com.payflow.payment.application.port.PaymentWorkflowStore;
import com.payflow.payment.application.saga.SagaRecoverySettings;
import com.payflow.payment.domain.model.Payment;
import com.payflow.payment.domain.model.PaymentSaga;
import com.payflow.payment.domain.model.PaymentSagaStatus;
import com.payflow.payment.domain.model.PaymentSagaStep;
import com.payflow.payment.domain.model.PaymentStatus;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Resolves a manual-review item and writes state, command outbox and audit atomically. */
@Service
public class ResolveManualReviewHandler {

    private final PaymentSagaStore sagas;
    private final PaymentWorkflowStore payments;
    private final OutboxAppender outbox;
    private final AuditLogAppender audit;
    private final SagaRecoverySettings settings;
    private final Clock clock;

    public ResolveManualReviewHandler(
            PaymentSagaStore sagas,
            PaymentWorkflowStore payments,
            OutboxAppender outbox,
            AuditLogAppender audit,
            SagaRecoverySettings settings,
            Clock clock) {
        this.sagas = sagas;
        this.payments = payments;
        this.outbox = outbox;
        this.audit = audit;
        this.settings = settings;
        this.clock = clock;
    }

    @Transactional
    public ManualReviewResolutionResult handle(ResolveManualReviewCommand command) {
        var storedSaga = sagas.findByPaymentId(command.paymentId())
                .orElseThrow(() -> new ManualReviewItemNotFoundException(command.paymentId()));
        var storedPayment = payments.findForWorkflow(command.paymentId())
                .orElseThrow(() -> new ManualReviewItemNotFoundException(command.paymentId()));
        PaymentSaga saga = storedSaga.saga();
        Payment payment = storedPayment.payment();
        requireResolvable(payment, saga, command.decision());

        Instant now = clock.instant();
        PaymentReviewAuditFacts before = facts(payment, saga);
        UUID commandEventId = apply(command.decision(), payment, saga, now);

        sagas.update(storedSaga);
        payments.updateWorkflow(storedPayment);
        audit.append(new AuditRecord(
                UUID.randomUUID(),
                "MANUAL_REVIEW_RESOLVED",
                "PAYMENT",
                payment.id(),
                command.actorSubject(),
                command.decisionCode(),
                command.correlationId(),
                before,
                facts(payment, saga),
                now));

        return new ManualReviewResolutionResult(
                payment.id(), payment.status(), saga.status(), saga.currentStep(), commandEventId, now);
    }

    private UUID apply(
            ManualReviewDecision decision, Payment payment, PaymentSaga saga, Instant now) {
        Instant nextDeadline = now.plus(settings.stepTimeout());
        return switch (decision) {
            case APPROVE_RISK -> {
                payment.approveRiskManualReview(now);
                saga.approveRiskManualReview(nextDeadline, now);
                yield outbox.append(
                        AccountEvents.RESERVE_REQUESTED,
                        PayFlowTopics.PAYMENT_EVENTS,
                        payment.id().toString(),
                        now,
                        new AccountReserveRequestedData(
                                payment.id(), payment.sourceAccountId(), payment.amount().amount(),
                                payment.amount().currency(), saga.deadlineAt()));
            }
            case REJECT_RISK -> {
                payment.rejectRiskManualReview(now);
                saga.rejectRiskManualReview(now);
                yield outbox.append(
                        PaymentEvents.PAYMENT_FAILED,
                        PayFlowTopics.PAYMENT_EVENTS,
                        payment.id().toString(),
                        now,
                        new PaymentFailedData(payment.id(), "MANUAL_REVIEW_RISK_REJECTED", now));
            }
            case RETRY_CURRENT_STEP -> retryCurrentStep(payment, saga, nextDeadline, now);
        };
    }

    private UUID retryCurrentStep(
            Payment payment, PaymentSaga saga, Instant nextDeadline, Instant now) {
        PaymentSagaStep step = saga.currentStep();
        if (step == PaymentSagaStep.RESERVE_FUNDS) {
            payment.resumeFundsReservationAfterManualReview(now);
        } else {
            payment.resumeProcessingAfterManualReview(now);
        }
        saga.retryCurrentStepAfterManualReview(nextDeadline, now);

        return switch (step) {
            case RESERVE_FUNDS -> outbox.append(
                    AccountEvents.RESERVE_REQUESTED, PayFlowTopics.PAYMENT_EVENTS,
                    payment.id().toString(), now,
                    new AccountReserveRequestedData(
                            payment.id(), payment.sourceAccountId(), payment.amount().amount(),
                            payment.amount().currency(), saga.deadlineAt()));
            case POST_LEDGER -> outbox.append(
                    LedgerEvents.POST_PAYMENT_REQUESTED, PayFlowTopics.PAYMENT_EVENTS,
                    payment.id().toString(), now,
                    new LedgerPostPaymentRequestedData(
                            payment.id(), payment.customerId(), payment.merchantId(),
                            payment.amount().amount(), payment.amount().currency()));
            case CAPTURE_FUNDS -> outbox.append(
                    AccountEvents.CAPTURE_REQUESTED, PayFlowTopics.PAYMENT_EVENTS,
                    payment.id().toString(), now,
                    new AccountCaptureRequestedData(
                            payment.id(), payment.sourceAccountId(), saga.reservationId(),
                            payment.amount().amount(), payment.amount().currency()));
            case RELEASE_FUNDS -> outbox.append(
                    AccountEvents.RELEASE_REQUESTED, PayFlowTopics.PAYMENT_EVENTS,
                    payment.id().toString(), now,
                    new AccountReleaseRequestedData(
                            payment.id(), payment.sourceAccountId(), saga.reservationId(),
                            payment.amount().amount(), payment.amount().currency(),
                            saga.lastErrorCode() == null ? "MANUAL_REVIEW_RETRY" : saga.lastErrorCode()));
            default -> throw new ManualReviewResolutionRejectedException(payment.id());
        };
    }

    private static void requireResolvable(
            Payment payment, PaymentSaga saga, ManualReviewDecision decision) {
        if (!payment.id().equals(saga.paymentId())
                || payment.status() != PaymentStatus.MANUAL_REVIEW_REQUIRED
                || saga.status() != PaymentSagaStatus.MANUAL_REVIEW_REQUIRED) {
            throw new ManualReviewResolutionRejectedException(payment.id());
        }
        boolean riskDecision = decision == ManualReviewDecision.APPROVE_RISK
                || decision == ManualReviewDecision.REJECT_RISK;
        if (riskDecision != (saga.currentStep() == PaymentSagaStep.RISK_ASSESSMENT)) {
            throw new ManualReviewResolutionRejectedException(payment.id());
        }
    }

    private static PaymentReviewAuditFacts facts(Payment payment, PaymentSaga saga) {
        return new PaymentReviewAuditFacts(
                payment.status(), saga.status(), saga.currentStep(), saga.reservationId(),
                saga.journalId(), saga.retryCount(), saga.lastErrorCode());
    }
}
