package com.payflow.ledger.application.handler;

import com.payflow.ledger.application.exception.RefundCommandContractException;
import com.payflow.ledger.application.exception.RefundWorkflowDataException;
import com.payflow.ledger.application.inbox.EventProcessingResult;
import com.payflow.ledger.application.inbox.IncomingEventIdentity;
import com.payflow.ledger.application.port.OutboxAppender;
import com.payflow.ledger.application.port.ProcessedEventStore;
import com.payflow.ledger.application.port.LedgerAccountDirectory;
import com.payflow.ledger.application.port.RefundJournalRecord;
import com.payflow.ledger.application.port.RefundJournalStore;
import com.payflow.ledger.application.refund.RefundJournalFactory;
import com.payflow.events.EventEnvelope;
import com.payflow.events.PayFlowTopics;
import com.payflow.events.ledger.LedgerEvents;
import com.payflow.events.ledger.LedgerRefundPostedData;
import com.payflow.events.refund.RefundEvents;
import com.payflow.events.refund.RefundRequestedData;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Posts one immutable refund reversal and its acknowledgement in a single local transaction. */
@Service
public class HandleRefundRequestedHandler {

    static final String CONSUMER_NAME = "ledger-refund-requested-v1";

    private final ProcessedEventStore inbox;
    private final LedgerAccountDirectory accounts;
    private final RefundJournalStore journals;
    private final OutboxAppender outbox;
    private final RefundJournalFactory factory;
    private final Clock clock;
    private final TransactionTemplate transactions;

    public HandleRefundRequestedHandler(
            ProcessedEventStore inbox,
            LedgerAccountDirectory accounts,
            RefundJournalStore journals,
            OutboxAppender outbox,
            RefundJournalFactory factory,
            Clock clock,
            TransactionTemplate transactions) {
        this.inbox = inbox;
        this.accounts = accounts;
        this.journals = journals;
        this.outbox = outbox;
        this.factory = factory;
        this.clock = clock;
        this.transactions = transactions;
    }

    public EventProcessingResult handle(EventEnvelope<RefundRequestedData> event) {
        requireContract(event);
        Instant now = clock.instant();
        return Objects.requireNonNull(transactions.execute(status -> {
            if (!inbox.recordIfNew(identity(event, now))) {
                return EventProcessingResult.DUPLICATE;
            }

            RefundRequestedData request = event.data();
            var existing = journals.findByRefundId(request.refundId());
            if (existing.isPresent()) {
                requireSameIntent(existing.orElseThrow(), request);
                return EventProcessingResult.BUSINESS_DUPLICATE;
            }

            var pair = accounts.findRefundAccounts(
                            request.merchantId(), request.accountId(), request.currency())
                    .orElseThrow(() -> new RefundWorkflowDataException(
                            "Ledger account mapping for merchant", request.merchantId()));
            var journal = factory.post(
                    UUID.randomUUID(),
                    pair.merchantAccountId(),
                    pair.customerAccountId(),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    request,
                    now);
            journals.save(journal, request);
            outbox.appendCausedBy(
                    LedgerEvents.REFUND_POSTED,
                    PayFlowTopics.LEDGER_EVENTS,
                    request.paymentId().toString(),
                    now,
                    new LedgerRefundPostedData(
                            request.refundId(),
                            request.paymentId(),
                            journal.id(),
                            request.accountId(),
                            request.amount(),
                            request.currency()),
                    event);
            return EventProcessingResult.PROCESSED;
        }));
    }

    private static IncomingEventIdentity identity(
            EventEnvelope<?> event, Instant processedAt) {
        return new IncomingEventIdentity(
                event.eventId(),
                CONSUMER_NAME,
                event.eventType(),
                event.aggregateId(),
                processedAt);
    }

    private static void requireContract(EventEnvelope<RefundRequestedData> event) {
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(event.data(), "event.data");
        boolean matches = RefundEvents.REFUND_REQUESTED.name().equals(event.eventType())
                && RefundEvents.REFUND_REQUESTED.version() == event.eventVersion()
                && RefundEvents.AGGREGATE_TYPE.equals(event.aggregateType())
                && event.data().paymentId().toString().equals(event.aggregateId());
        if (!matches) {
            throw new RefundCommandContractException(
                    "refund.requested envelope does not match payload identity");
        }
    }

    private static void requireSameIntent(
            RefundJournalRecord existing, RefundRequestedData request) {
        boolean same = existing.refundId().equals(request.refundId())
                && existing.paymentId().equals(request.paymentId())
                && existing.merchantId().equals(request.merchantId())
                && existing.sourceAccountId().equals(request.accountId())
                && existing.amount().compareTo(request.amount()) == 0
                && existing.currency().equals(request.currency());
        if (!same) {
            throw new RefundCommandContractException(
                    "existing refund journal does not match duplicate intent");
        }
    }
}
