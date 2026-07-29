package com.payflow.accountledger.ledger.application.handler;

import com.payflow.accountledger.application.exception.WorkflowCommandContractException;
import com.payflow.accountledger.application.inbox.EventProcessingResult;
import com.payflow.accountledger.application.inbox.IncomingEventIdentity;
import com.payflow.accountledger.application.port.OutboxAppender;
import com.payflow.accountledger.application.port.ProcessedEventStore;
import com.payflow.accountledger.ledger.application.payment.PaymentJournalFactory;
import com.payflow.accountledger.ledger.application.port.LedgerAccountDirectory;
import com.payflow.accountledger.ledger.application.port.PaymentJournalRecord;
import com.payflow.accountledger.ledger.application.port.PaymentJournalStore;
import com.payflow.events.EventEnvelope;
import com.payflow.events.PayFlowTopics;
import com.payflow.events.ledger.LedgerEvents;
import com.payflow.events.ledger.LedgerPaymentPostedData;
import com.payflow.events.ledger.LedgerPaymentPostingFailedData;
import com.payflow.events.ledger.LedgerPostPaymentRequestedData;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Posts one immutable PAYMENT_CAPTURE journal and its outcome in one transaction. */
@Service
public class HandlePostPaymentRequestedHandler {

    static final String CONSUMER_NAME = "ledger-post-payment-v1";
    static final String ACCOUNT_MAPPING_NOT_FOUND = "LEDGER_ACCOUNT_MAPPING_NOT_FOUND";

    private final ProcessedEventStore inbox;
    private final LedgerAccountDirectory accounts;
    private final PaymentJournalStore journals;
    private final OutboxAppender outbox;
    private final PaymentJournalFactory factory;
    private final Clock clock;
    private final TransactionTemplate transactions;

    public HandlePostPaymentRequestedHandler(
            ProcessedEventStore inbox,
            LedgerAccountDirectory accounts,
            PaymentJournalStore journals,
            OutboxAppender outbox,
            PaymentJournalFactory factory,
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

    public EventProcessingResult handle(EventEnvelope<LedgerPostPaymentRequestedData> event) {
        requireContract(event);
        Instant now = clock.instant();
        return Objects.requireNonNull(transactions.execute(status -> {
            if (!inbox.recordIfNew(identity(event, now))) {
                return EventProcessingResult.DUPLICATE;
            }

            var command = event.data();
            var existing = journals.findByPaymentId(command.paymentId());
            if (existing.isPresent()) {
                requireSameIntent(existing.orElseThrow(), command);
                return EventProcessingResult.BUSINESS_DUPLICATE;
            }

            var pair = accounts.findPaymentAccounts(
                            command.customerId(), command.merchantId(), command.currency())
                    .orElse(null);
            if (pair == null) {
                outbox.appendCausedBy(
                        LedgerEvents.PAYMENT_POSTING_FAILED,
                        PayFlowTopics.LEDGER_EVENTS,
                        command.paymentId().toString(),
                        now,
                        new LedgerPaymentPostingFailedData(
                                command.paymentId(), ACCOUNT_MAPPING_NOT_FOUND, now),
                        event);
                return EventProcessingResult.PROCESSED;
            }

            var journal = factory.post(
                    UUID.randomUUID(),
                    pair.customerAccountId(),
                    pair.merchantAccountId(),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    command,
                    now);
            journals.save(journal, command);
            outbox.appendCausedBy(
                    LedgerEvents.PAYMENT_POSTED,
                    PayFlowTopics.LEDGER_EVENTS,
                    command.paymentId().toString(),
                    now,
                    new LedgerPaymentPostedData(
                            command.paymentId(), journal.id(), command.amount(), command.currency()),
                    event);
            return EventProcessingResult.PROCESSED;
        }));
    }

    private static IncomingEventIdentity identity(EventEnvelope<?> event, Instant processedAt) {
        return new IncomingEventIdentity(
                event.eventId(), CONSUMER_NAME, event.eventType(), event.aggregateId(), processedAt);
    }

    private static void requireContract(EventEnvelope<LedgerPostPaymentRequestedData> event) {
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(event.data(), "event.data");
        boolean matches = LedgerEvents.POST_PAYMENT_REQUESTED.name().equals(event.eventType())
                && LedgerEvents.POST_PAYMENT_REQUESTED.version() == event.eventVersion()
                && LedgerEvents.AGGREGATE_TYPE.equals(event.aggregateType())
                && event.data().paymentId().toString().equals(event.aggregateId());
        if (!matches) {
            throw new WorkflowCommandContractException(
                    "ledger.post-payment.requested envelope does not match payload identity");
        }
    }

    private static void requireSameIntent(
            PaymentJournalRecord existing, LedgerPostPaymentRequestedData command) {
        boolean same = existing.paymentId().equals(command.paymentId())
                && existing.customerId().equals(command.customerId())
                && existing.merchantId().equals(command.merchantId())
                && existing.amount().compareTo(command.amount()) == 0
                && existing.currency().equals(command.currency());
        if (!same) {
            throw new WorkflowCommandContractException(
                    "existing payment journal does not match duplicate intent");
        }
    }
}
