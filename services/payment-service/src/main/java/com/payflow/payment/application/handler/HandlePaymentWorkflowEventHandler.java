package com.payflow.payment.application.handler;

import com.payflow.events.EventEnvelope;
import com.payflow.events.EventType;
import com.payflow.events.PayFlowTopics;
import com.payflow.events.account.AccountEvents;
import com.payflow.events.account.AccountFundsCapturedData;
import com.payflow.events.account.AccountFundsReleasedData;
import com.payflow.events.account.AccountFundsReservationFailedData;
import com.payflow.events.account.AccountFundsReservedData;
import com.payflow.events.ledger.LedgerEvents;
import com.payflow.events.ledger.LedgerPaymentPostedData;
import com.payflow.events.ledger.LedgerPaymentPostingFailedData;
import com.payflow.events.payment.PaymentEvents;
import com.payflow.events.payment.PaymentFailedData;
import com.payflow.events.payment.PaymentManualReviewRequiredData;
import com.payflow.events.risk.RiskAssessmentCompletedData;
import com.payflow.events.risk.RiskEvents;
import com.payflow.payment.application.exception.PaymentSagaContractMismatchException;
import com.payflow.payment.application.exception.SagaRecoveryDataException;
import com.payflow.payment.application.inbox.EventProcessingResult;
import com.payflow.payment.application.inbox.IncomingEventIdentity;
import com.payflow.payment.application.port.OutboxAppender;
import com.payflow.payment.application.port.PaymentSagaStore;
import com.payflow.payment.application.port.PaymentWorkflowStore;
import com.payflow.payment.application.port.ProcessedEventStore;
import com.payflow.payment.application.saga.ApplyRiskAssessmentPolicy;
import com.payflow.payment.application.saga.PaymentFinalizationPolicy;
import com.payflow.payment.application.saga.PaymentFundsReservationPolicy;
import com.payflow.payment.application.saga.PaymentSagaRecoveryPolicy;
import com.payflow.payment.application.saga.SagaRecoveryAction;
import com.payflow.payment.application.saga.SagaRecoverySettings;
import com.payflow.payment.application.saga.VersionedPayment;
import com.payflow.payment.application.saga.VersionedPaymentSaga;
import com.payflow.payment.domain.model.Payment;
import com.payflow.payment.domain.model.PaymentRiskAction;
import com.payflow.payment.domain.model.PaymentSaga;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Use case consumer cho Payment Saga với giao dịch transactional.
 *
 * <p>Mỗi public method đều thực hiện inbox insert, Payment/Saga transition và outgoing outbox append
 * trong cùng 1 local transaction. Việc Kafka acknowledgement thuộc về listener và chỉ xảy ra
 * sau khi một trong các method này trả về kết quả thành công.
 */
@Service
public class HandlePaymentWorkflowEventHandler {

    static final String CONSUMER_NAME = "payment-saga-orchestrator-v1";
    static final String RISK_REVIEW_REQUIRED = "RISK_REVIEW_REQUIRED";

    private final ProcessedEventStore inbox;
    private final PaymentWorkflowStore payments;
    private final PaymentSagaStore sagas;
    private final OutboxAppender outbox;
    private final ApplyRiskAssessmentPolicy riskPolicy;
    private final PaymentFundsReservationPolicy reservationPolicy;
    private final PaymentFinalizationPolicy finalizationPolicy;
    private final PaymentSagaRecoveryPolicy recoveryPolicy;
    private final SagaRecoverySettings settings;
    private final Clock clock;
    private final TransactionTemplate transactions;

    public HandlePaymentWorkflowEventHandler(
            ProcessedEventStore inbox,
            PaymentWorkflowStore payments,
            PaymentSagaStore sagas,
            OutboxAppender outbox,
            ApplyRiskAssessmentPolicy riskPolicy,
            PaymentFundsReservationPolicy reservationPolicy,
            PaymentFinalizationPolicy finalizationPolicy,
            PaymentSagaRecoveryPolicy recoveryPolicy,
            SagaRecoverySettings settings,
            Clock clock,
            TransactionTemplate transactions) {
        this.inbox = inbox;
        this.payments = payments;
        this.sagas = sagas;
        this.outbox = outbox;
        this.riskPolicy = riskPolicy;
        this.reservationPolicy = reservationPolicy;
        this.finalizationPolicy = finalizationPolicy;
        this.recoveryPolicy = recoveryPolicy;
        this.settings = settings;
        this.clock = clock;
        this.transactions = transactions;
    }

    public EventProcessingResult handleRiskAssessment(
            EventEnvelope<RiskAssessmentCompletedData> event) {
        UUID paymentId = event.data().paymentId();
        return process(event, RiskEvents.RISK_ASSESSMENT_COMPLETED, paymentId, (payment, saga, now) -> {
            PaymentRiskAction action = riskPolicy.apply(payment, event.data(), now);
            return switch (action) {
                case REQUEST_FUNDS_RESERVATION -> {
                    saga.recordRiskApproved(nextDeadline(now), now);
                    yield WorkflowChange.both(
                            AccountEvents.RESERVE_REQUESTED,
                            reservationPolicy.request(payment, now, saga.deadlineAt()));
                }
                case PUBLISH_PAYMENT_FAILED -> {
                    saga.failBeforeLedger(ApplyRiskAssessmentPolicy.RISK_REJECTED_FAILURE_CODE, now);
                    yield WorkflowChange.both(
                            PaymentEvents.PAYMENT_FAILED,
                            new PaymentFailedData(
                                    payment.id(),
                                    ApplyRiskAssessmentPolicy.RISK_REJECTED_FAILURE_CODE,
                                    now));
                }
                case AWAIT_MANUAL_REVIEW -> {
                    saga.requireManualReview(RISK_REVIEW_REQUIRED, now);
                    yield WorkflowChange.both(
                            PaymentEvents.MANUAL_REVIEW_REQUIRED,
                            new PaymentManualReviewRequiredData(
                                    payment.id(), saga.currentStep().name(), RISK_REVIEW_REQUIRED));
                }
            };
        });
    }

    public EventProcessingResult handleFundsReserved(
            EventEnvelope<AccountFundsReservedData> event) {
        UUID paymentId = event.data().paymentId();
        return process(event, AccountEvents.FUNDS_RESERVED, paymentId, (payment, saga, now) -> {
            var command = reservationPolicy.applyReserved(payment, event.data(), now);
            saga.recordFundsReserved(event.data().reservationId(), nextDeadline(now), now);
            return WorkflowChange.both(LedgerEvents.POST_PAYMENT_REQUESTED, command);
        });
    }

    public EventProcessingResult handleFundsReservationFailed(
            EventEnvelope<AccountFundsReservationFailedData> event) {
        UUID paymentId = event.data().paymentId();
        return process(event, AccountEvents.FUNDS_RESERVATION_FAILED, paymentId, (payment, saga, now) -> {
            PaymentFailedData outcome = reservationPolicy.applyFailed(payment, event.data(), now);
            saga.failBeforeLedger(event.data().reasonCode(), now);
            return WorkflowChange.both(PaymentEvents.PAYMENT_FAILED, outcome);
        });
    }

    public EventProcessingResult handleLedgerPosted(
            EventEnvelope<LedgerPaymentPostedData> event) {
        UUID paymentId = event.data().paymentId();
        return process(event, LedgerEvents.PAYMENT_POSTED, paymentId, (payment, saga, now) -> {
            var command = finalizationPolicy.requestCapture(
                    payment, reservationFact(payment, saga), event.data());
            saga.recordLedgerPosted(event.data().journalId(), nextDeadline(now), now);
            return WorkflowChange.saga(AccountEvents.CAPTURE_REQUESTED, command);
        });
    }

    public EventProcessingResult handleLedgerPostingFailed(
            EventEnvelope<LedgerPaymentPostingFailedData> event) {
        UUID paymentId = event.data().paymentId();
        return process(event, LedgerEvents.PAYMENT_POSTING_FAILED, paymentId, (payment, saga, now) -> {
            // Event v1 là một kết quả dứt điểm hoặc là kết quả sau khi producer đã cạn số lần retry. Các response bị thiếu sẽ
            // được retry bởi scheduler dựa theo deadline được lưu trữ; Payment không suy ra khả năng retry từ một
            // failure code dạng tự do.
            SagaRecoveryAction action = recoveryPolicy.onLedgerPostingFailed(
                    payment,
                    saga,
                    reservationFact(payment, saga),
                    event.data(),
                    false,
                    now,
                    nextDeadline(now),
                    settings.maxRetries());
            if (action instanceof SagaRecoveryAction.ReleaseFunds release) {
                return WorkflowChange.saga(AccountEvents.RELEASE_REQUESTED, release.command());
            }
            if (action instanceof SagaRecoveryAction.ManualReview manualReview) {
                return WorkflowChange.both(
                        PaymentEvents.MANUAL_REVIEW_REQUIRED, manualReview.eventData());
            }
            throw new IllegalStateException("definitive Ledger failure must release or require review");
        });
    }

    public EventProcessingResult handleFundsCaptured(
            EventEnvelope<AccountFundsCapturedData> event) {
        UUID paymentId = event.data().paymentId();
        return process(event, AccountEvents.FUNDS_CAPTURED, paymentId, (payment, saga, now) -> {
            var reservation = reservationFact(payment, saga);
            var ledger = ledgerFact(payment, saga);
            var outcome = finalizationPolicy.complete(payment, reservation, ledger, event.data(), now);
            saga.complete(now);
            return WorkflowChange.both(PaymentEvents.PAYMENT_SUCCEEDED, outcome);
        });
    }

    public EventProcessingResult handleFundsReleased(
            EventEnvelope<AccountFundsReleasedData> event) {
        UUID paymentId = event.data().paymentId();
        return process(event, AccountEvents.FUNDS_RELEASED, paymentId, (payment, saga, now) -> {
            PaymentFailedData outcome = recoveryPolicy.onFundsReleased(
                    payment, saga, reservationFact(payment, saga), event.data(), now);
            return WorkflowChange.both(PaymentEvents.PAYMENT_FAILED, outcome);
        });
    }

    private EventProcessingResult process(
            EventEnvelope<?> event,
            EventType expectedType,
            UUID paymentId,
            WorkflowMutation mutation) {
        requireContract(event, expectedType, paymentId);
        Instant processedAt = clock.instant();
        return Objects.requireNonNull(transactions.execute(status -> {
            boolean isNew = inbox.recordIfNew(new IncomingEventIdentity(
                    event.eventId(),
                    CONSUMER_NAME,
                    event.eventType(),
                    event.aggregateId(),
                    processedAt));
            if (!isNew) {
                return EventProcessingResult.DUPLICATE;
            }

            VersionedPayment storedPayment = payments.findForWorkflow(paymentId)
                    .orElseThrow(() -> new SagaRecoveryDataException("Payment", paymentId));
            VersionedPaymentSaga storedSaga = sagas.findByPaymentId(paymentId)
                    .orElseThrow(() -> new SagaRecoveryDataException("PaymentSaga", paymentId));
            Payment payment = storedPayment.payment();
            PaymentSaga saga = storedSaga.saga();
            WorkflowChange change = mutation.apply(payment, saga, processedAt);

            if (change.paymentChanged()) {
                payments.updateWorkflow(storedPayment);
            }
            if (change.sagaChanged()) {
                sagas.update(storedSaga);
            }
            outbox.appendCausedBy(
                    change.type(),
                    PayFlowTopics.PAYMENT_EVENTS,
                    paymentId.toString(),
                    processedAt,
                    change.data(),
                    event);
            return EventProcessingResult.PROCESSED;
        }));
    }

    private Instant nextDeadline(Instant now) {
        return now.plus(settings.stepTimeout());
    }

    private static AccountFundsReservedData reservationFact(Payment payment, PaymentSaga saga) {
        if (saga.reservationId() == null) {
            throw new SagaRecoveryDataException("Reservation", saga.id());
        }
        return new AccountFundsReservedData(
                payment.id(),
                payment.sourceAccountId(),
                saga.reservationId(),
                payment.amount().amount(),
                payment.amount().currency());
    }

    private static LedgerPaymentPostedData ledgerFact(Payment payment, PaymentSaga saga) {
        if (saga.journalId() == null) {
            throw new SagaRecoveryDataException("Journal", saga.id());
        }
        return new LedgerPaymentPostedData(
                payment.id(),
                saga.journalId(),
                payment.amount().amount(),
                payment.amount().currency());
    }

    private static void requireContract(
            EventEnvelope<?> event, EventType expectedType, UUID paymentId) {
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(paymentId, "paymentId");
        boolean contractMatches = expectedType.name().equals(event.eventType())
                && expectedType.version() == event.eventVersion()
                && expectedType.aggregateType().equals(event.aggregateType());
        if (!contractMatches) {
            throw new PaymentSagaContractMismatchException(
                    "incoming contract",
                    expectedType.name() + " v" + expectedType.version(),
                    event.eventType() + " v" + event.eventVersion());
        }
        if (!paymentId.toString().equals(event.aggregateId())) {
            throw new PaymentSagaContractMismatchException(
                    "incoming aggregateId", paymentId, event.aggregateId());
        }
    }

    @FunctionalInterface
    private interface WorkflowMutation {
        WorkflowChange apply(Payment payment, PaymentSaga saga, Instant processedAt);
    }

    private record WorkflowChange(
            boolean paymentChanged,
            boolean sagaChanged,
            EventType type,
            Object data) {

        private WorkflowChange {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(data, "data");
        }

        static WorkflowChange both(EventType type, Object data) {
            return new WorkflowChange(true, true, type, data);
        }

        static WorkflowChange saga(EventType type, Object data) {
            return new WorkflowChange(false, true, type, data);
        }
    }
}
