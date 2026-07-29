package com.payflow.payment.application.handler;

import com.payflow.events.EventEnvelope;
import com.payflow.events.EventType;
import com.payflow.events.PayFlowTopics;
import com.payflow.events.account.AccountEvents;
import com.payflow.events.account.AccountRefundCreditedData;
import com.payflow.events.ledger.LedgerEvents;
import com.payflow.events.ledger.LedgerRefundPostedData;
import com.payflow.events.ledger.LedgerRefundPostingFailedData;
import com.payflow.events.refund.RefundEvents;
import com.payflow.payment.application.exception.PaymentSagaContractMismatchException;
import com.payflow.payment.application.exception.RefundWorkflowDataException;
import com.payflow.payment.application.inbox.EventProcessingResult;
import com.payflow.payment.application.inbox.IncomingEventIdentity;
import com.payflow.payment.application.port.OutboxAppender;
import com.payflow.payment.application.port.ProcessedEventStore;
import com.payflow.payment.application.port.RefundPaymentStore;
import com.payflow.payment.application.port.RefundRepository;
import com.payflow.payment.application.refund.RefundFinalizationPolicy;
import com.payflow.payment.domain.model.Payment;
import com.payflow.payment.domain.model.Refund;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Transactional ADR-021 orchestrator for refund financial outcomes. */
@Service
public class HandleRefundWorkflowEventHandler {

    static final String CONSUMER_NAME = "payment-refund-orchestrator-v1";

    private final ProcessedEventStore inbox;
    private final RefundPaymentStore payments;
    private final RefundRepository refunds;
    private final OutboxAppender outbox;
    private final RefundFinalizationPolicy policy;
    private final Clock clock;
    private final TransactionTemplate transactions;

    public HandleRefundWorkflowEventHandler(
            ProcessedEventStore inbox,
            RefundPaymentStore payments,
            RefundRepository refunds,
            OutboxAppender outbox,
            RefundFinalizationPolicy policy,
            Clock clock,
            TransactionTemplate transactions) {
        this.inbox = inbox;
        this.payments = payments;
        this.refunds = refunds;
        this.outbox = outbox;
        this.policy = policy;
        this.clock = clock;
        this.transactions = transactions;
    }

    public EventProcessingResult handleLedgerRefundPosted(
            EventEnvelope<LedgerRefundPostedData> event) {
        return process(
                event,
                LedgerEvents.REFUND_POSTED,
                event.data().paymentId(),
                event.data().refundId(),
                (payment, refund, now) -> new WorkflowChange(
                        false,
                        AccountEvents.REFUND_CREDIT_REQUESTED,
                        PayFlowTopics.PAYMENT_EVENTS,
                        policy.requestCredit(payment, refund, event.data(), now)));
    }

    public EventProcessingResult handleAccountRefundCredited(
            EventEnvelope<AccountRefundCreditedData> event) {
        return process(
                event,
                AccountEvents.REFUND_CREDITED,
                event.data().paymentId(),
                event.data().refundId(),
                (payment, refund, now) -> {
                    if (refund.ledgerJournalId() == null) {
                        throw new RefundWorkflowDataException("LedgerJournal", refund.id());
                    }
                    LedgerRefundPostedData ledger = new LedgerRefundPostedData(
                            refund.id(),
                            payment.id(),
                            refund.ledgerJournalId(),
                            payment.sourceAccountId(),
                            refund.amount().amount(),
                            refund.amount().currency());
                    return new WorkflowChange(
                            true,
                            RefundEvents.REFUND_SUCCEEDED,
                            PayFlowTopics.REFUND_EVENTS,
                            policy.complete(payment, refund, ledger, event.data(), now));
                });
    }

    public EventProcessingResult handleLedgerRefundPostingFailed(
            EventEnvelope<LedgerRefundPostingFailedData> event) {
        return process(
                event,
                LedgerEvents.REFUND_POSTING_FAILED,
                event.data().paymentId(),
                event.data().refundId(),
                (payment, refund, now) -> new WorkflowChange(
                        true,
                        RefundEvents.REFUND_FAILED,
                        PayFlowTopics.REFUND_EVENTS,
                        policy.failBeforeJournal(payment, refund, event.data(), now)));
    }

    private EventProcessingResult process(
            EventEnvelope<?> event,
            EventType expectedType,
            UUID paymentId,
            UUID refundId,
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

            // Consistent lock order with refund intake: Payment first, Refund second.
            Payment payment = payments.findForRefundWorkflow(paymentId)
                    .orElseThrow(() -> new RefundWorkflowDataException("Payment", paymentId));
            Refund refund = refunds.findForWorkflow(refundId)
                    .orElseThrow(() -> new RefundWorkflowDataException("Refund", refundId));
            WorkflowChange change = mutation.apply(payment, refund, processedAt);

            if (change.paymentChanged()) {
                payments.updateRefundState(payment);
            }
            refunds.updateWorkflow(refund);
            outbox.appendCausedBy(
                    change.type(),
                    change.topic(),
                    paymentId.toString(),
                    processedAt,
                    change.data(),
                    event);
            return EventProcessingResult.PROCESSED;
        }));
    }

    private static void requireContract(
            EventEnvelope<?> event, EventType expectedType, UUID paymentId) {
        Objects.requireNonNull(event, "event");
        boolean matches = expectedType.name().equals(event.eventType())
                && expectedType.version() == event.eventVersion()
                && expectedType.aggregateType().equals(event.aggregateType());
        if (!matches) {
            throw new PaymentSagaContractMismatchException(
                    "incoming refund contract",
                    expectedType.name() + " v" + expectedType.version(),
                    event.eventType() + " v" + event.eventVersion());
        }
        if (!paymentId.toString().equals(event.aggregateId())) {
            throw new PaymentSagaContractMismatchException(
                    "incoming refund aggregateId", paymentId, event.aggregateId());
        }
    }

    @FunctionalInterface
    private interface WorkflowMutation {
        WorkflowChange apply(Payment payment, Refund refund, Instant processedAt);
    }

    private record WorkflowChange(
            boolean paymentChanged, EventType type, String topic, Object data) {

        private WorkflowChange {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(topic, "topic");
            Objects.requireNonNull(data, "data");
        }
    }
}
