package com.payflow.accountledger.account.application.handler;

import com.payflow.accountledger.account.application.port.AccountRefundStore;
import com.payflow.accountledger.account.application.refund.RefundCreditPolicy;
import com.payflow.accountledger.application.exception.RefundCommandContractException;
import com.payflow.accountledger.application.exception.RefundWorkflowDataException;
import com.payflow.accountledger.application.inbox.EventProcessingResult;
import com.payflow.accountledger.application.inbox.IncomingEventIdentity;
import com.payflow.accountledger.application.port.OutboxAppender;
import com.payflow.accountledger.application.port.ProcessedEventStore;
import com.payflow.events.EventEnvelope;
import com.payflow.events.PayFlowTopics;
import com.payflow.events.account.AccountEvents;
import com.payflow.events.account.AccountRefundCreditRequestedData;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Credits one Account refund and acknowledges it in the same local transaction. */
@Service
public class HandleRefundCreditRequestedHandler {

    static final String CONSUMER_NAME = "account-refund-credit-v1";

    private final ProcessedEventStore inbox;
    private final AccountRefundStore store;
    private final OutboxAppender outbox;
    private final RefundCreditPolicy policy;
    private final Clock clock;
    private final TransactionTemplate transactions;

    public HandleRefundCreditRequestedHandler(
            ProcessedEventStore inbox,
            AccountRefundStore store,
            OutboxAppender outbox,
            RefundCreditPolicy policy,
            Clock clock,
            TransactionTemplate transactions) {
        this.inbox = inbox;
        this.store = store;
        this.outbox = outbox;
        this.policy = policy;
        this.clock = clock;
        this.transactions = transactions;
    }

    public EventProcessingResult handle(EventEnvelope<AccountRefundCreditRequestedData> event) {
        requireContract(event);
        Instant now = clock.instant();
        return Objects.requireNonNull(transactions.execute(status -> {
            if (!inbox.recordIfNew(new IncomingEventIdentity(
                    event.eventId(),
                    CONSUMER_NAME,
                    event.eventType(),
                    event.aggregateId(),
                    now))) {
                return EventProcessingResult.DUPLICATE;
            }

            AccountRefundCreditRequestedData command = event.data();
            var account = store.findAccountForUpdate(command.accountId())
                    .orElseThrow(() -> new RefundWorkflowDataException(
                            "Account", command.accountId()));
            var existing = store.findCreditByRefundId(command.refundId()).orElse(null);
            var result = policy.credit(account, existing, command, UUID.randomUUID(), now);
            if (result.duplicate()) {
                return EventProcessingResult.BUSINESS_DUPLICATE;
            }

            store.updateAccount(account);
            store.saveCredit(result.credit());
            outbox.appendCausedBy(
                    AccountEvents.REFUND_CREDITED,
                    PayFlowTopics.ACCOUNT_EVENTS,
                    command.paymentId().toString(),
                    now,
                    result.eventData(),
                    event);
            return EventProcessingResult.PROCESSED;
        }));
    }

    private static void requireContract(
            EventEnvelope<AccountRefundCreditRequestedData> event) {
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(event.data(), "event.data");
        boolean matches = AccountEvents.REFUND_CREDIT_REQUESTED.name().equals(event.eventType())
                && AccountEvents.REFUND_CREDIT_REQUESTED.version() == event.eventVersion()
                && AccountEvents.AGGREGATE_TYPE.equals(event.aggregateType())
                && event.data().paymentId().toString().equals(event.aggregateId());
        if (!matches) {
            throw new RefundCommandContractException(
                    "account.refund-credit.requested envelope does not match payload identity");
        }
    }
}
