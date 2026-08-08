package com.payflow.account.application.handler;

import com.payflow.account.application.port.AccountReservationStore;
import com.payflow.account.application.reservation.FundsReservationFailedResult;
import com.payflow.account.application.reservation.FundsReservedResult;
import com.payflow.account.application.reservation.ReserveFundsPolicy;
import com.payflow.account.application.exception.WorkflowCommandContractException;
import com.payflow.account.application.inbox.EventProcessingResult;
import com.payflow.account.application.inbox.IncomingEventIdentity;
import com.payflow.account.application.port.OutboxAppender;
import com.payflow.account.application.port.ProcessedEventStore;
import com.payflow.events.EventEnvelope;
import com.payflow.events.PayFlowTopics;
import com.payflow.events.account.AccountEvents;
import com.payflow.events.account.AccountFundsReservationFailedData;
import com.payflow.events.account.AccountReserveRequestedData;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Reserves Account funds with inbox, balance, reservation and outcome in one transaction. */
@Service
public class HandleReserveFundsRequestedHandler {

    static final String CONSUMER_NAME = "account-reserve-funds-v1";
    static final String ACCOUNT_NOT_FOUND = "ACCOUNT_NOT_FOUND";

    private final ProcessedEventStore inbox;
    private final AccountReservationStore store;
    private final OutboxAppender outbox;
    private final ReserveFundsPolicy policy;
    private final Clock clock;
    private final TransactionTemplate transactions;

    public HandleReserveFundsRequestedHandler(
            ProcessedEventStore inbox,
            AccountReservationStore store,
            OutboxAppender outbox,
            ReserveFundsPolicy policy,
            Clock clock,
            TransactionTemplate transactions) {
        this.inbox = inbox;
        this.store = store;
        this.outbox = outbox;
        this.policy = policy;
        this.clock = clock;
        this.transactions = transactions;
    }

    public EventProcessingResult handle(EventEnvelope<AccountReserveRequestedData> event) {
        requireContract(event);
        Instant now = clock.instant();
        return Objects.requireNonNull(transactions.execute(status -> {
            if (!inbox.recordIfNew(identity(event, now))) {
                return EventProcessingResult.DUPLICATE;
            }

            var command = event.data();
            var account = store.findAccountForUpdate(command.accountId()).orElse(null);
            if (account == null) {
                appendFailure(event, new AccountFundsReservationFailedData(
                        command.paymentId(), command.accountId(), ACCOUNT_NOT_FOUND), now);
                return EventProcessingResult.PROCESSED;
            }

            var existing = store.findReservationByPaymentId(command.paymentId()).orElse(null);
            var result = policy.reserve(account, existing, command, UUID.randomUUID(), now);
            if (result instanceof FundsReservedResult reserved) {
                if (reserved.duplicate()) {
                    return EventProcessingResult.BUSINESS_DUPLICATE;
                }
                store.updateAccount(account);
                store.saveReservation(reserved.reservation());
                outbox.appendCausedBy(
                        AccountEvents.FUNDS_RESERVED,
                        PayFlowTopics.ACCOUNT_EVENTS,
                        command.paymentId().toString(),
                        now,
                        reserved.eventData(),
                        event);
            } else {
                appendFailure(
                        event, ((FundsReservationFailedResult) result).eventData(), now);
            }
            return EventProcessingResult.PROCESSED;
        }));
    }

    private void appendFailure(
            EventEnvelope<AccountReserveRequestedData> event,
            AccountFundsReservationFailedData data,
            Instant now) {
        outbox.appendCausedBy(
                AccountEvents.FUNDS_RESERVATION_FAILED,
                PayFlowTopics.ACCOUNT_EVENTS,
                event.aggregateId(),
                now,
                data,
                event);
    }

    private static IncomingEventIdentity identity(EventEnvelope<?> event, Instant processedAt) {
        return new IncomingEventIdentity(
                event.eventId(), CONSUMER_NAME, event.eventType(), event.aggregateId(), processedAt);
    }

    private static void requireContract(EventEnvelope<AccountReserveRequestedData> event) {
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(event.data(), "event.data");
        boolean matches = AccountEvents.RESERVE_REQUESTED.name().equals(event.eventType())
                && AccountEvents.RESERVE_REQUESTED.version() == event.eventVersion()
                && AccountEvents.AGGREGATE_TYPE.equals(event.aggregateType())
                && event.data().paymentId().toString().equals(event.aggregateId());
        if (!matches) {
            throw new WorkflowCommandContractException(
                    "account.reserve.requested envelope does not match payload identity");
        }
    }
}
