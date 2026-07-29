package com.payflow.accountledger.account.application.handler;

import com.payflow.accountledger.account.application.port.AccountReservationStore;
import com.payflow.accountledger.account.application.reservation.CaptureFundsPolicy;
import com.payflow.accountledger.application.exception.WorkflowCommandContractException;
import com.payflow.accountledger.application.exception.WorkflowDataException;
import com.payflow.accountledger.application.inbox.EventProcessingResult;
import com.payflow.accountledger.application.inbox.IncomingEventIdentity;
import com.payflow.accountledger.application.port.OutboxAppender;
import com.payflow.accountledger.application.port.ProcessedEventStore;
import com.payflow.events.EventEnvelope;
import com.payflow.events.PayFlowTopics;
import com.payflow.events.account.AccountCaptureRequestedData;
import com.payflow.events.account.AccountEvents;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Captures only an ACTIVE matching reservation after Ledger has acknowledged its journal. */
@Service
public class HandleCaptureFundsRequestedHandler {

    static final String CONSUMER_NAME = "account-capture-funds-v1";

    private final ProcessedEventStore inbox;
    private final AccountReservationStore store;
    private final OutboxAppender outbox;
    private final CaptureFundsPolicy policy;
    private final Clock clock;
    private final TransactionTemplate transactions;

    public HandleCaptureFundsRequestedHandler(
            ProcessedEventStore inbox,
            AccountReservationStore store,
            OutboxAppender outbox,
            CaptureFundsPolicy policy,
            Clock clock,
            TransactionTemplate transactions) {
        this.inbox = inbox;
        this.store = store;
        this.outbox = outbox;
        this.policy = policy;
        this.clock = clock;
        this.transactions = transactions;
    }

    public EventProcessingResult handle(EventEnvelope<AccountCaptureRequestedData> event) {
        requireContract(event);
        Instant now = clock.instant();
        return Objects.requireNonNull(transactions.execute(status -> {
            if (!inbox.recordIfNew(identity(event, now))) {
                return EventProcessingResult.DUPLICATE;
            }
            var command = event.data();
            var account = store.findAccountForUpdate(command.accountId())
                    .orElseThrow(() -> new WorkflowDataException("Account", command.accountId()));
            var reservation = store.findReservationForUpdate(command.reservationId())
                    .orElseThrow(() -> new WorkflowDataException(
                            "Reservation", command.reservationId()));
            var result = policy.capture(account, reservation, command, now);
            if (result.duplicate()) {
                return EventProcessingResult.BUSINESS_DUPLICATE;
            }

            store.updateAccount(account);
            store.updateReservation(reservation);
            outbox.appendCausedBy(
                    AccountEvents.FUNDS_CAPTURED,
                    PayFlowTopics.ACCOUNT_EVENTS,
                    command.paymentId().toString(),
                    now,
                    result.eventData(),
                    event);
            return EventProcessingResult.PROCESSED;
        }));
    }

    private static IncomingEventIdentity identity(EventEnvelope<?> event, Instant processedAt) {
        return new IncomingEventIdentity(
                event.eventId(), CONSUMER_NAME, event.eventType(), event.aggregateId(), processedAt);
    }

    private static void requireContract(EventEnvelope<AccountCaptureRequestedData> event) {
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(event.data(), "event.data");
        boolean matches = AccountEvents.CAPTURE_REQUESTED.name().equals(event.eventType())
                && AccountEvents.CAPTURE_REQUESTED.version() == event.eventVersion()
                && AccountEvents.AGGREGATE_TYPE.equals(event.aggregateType())
                && event.data().paymentId().toString().equals(event.aggregateId());
        if (!matches) {
            throw new WorkflowCommandContractException(
                    "account.capture.requested envelope does not match payload identity");
        }
    }
}
