package com.payflow.accountledger.account.application.refund;

import com.payflow.accountledger.account.domain.exception.AccountInvariantViolationException;
import com.payflow.events.EventEnvelope;
import com.payflow.events.account.AccountEvents;
import com.payflow.events.account.AccountRefundCreditRequestedData;
import com.payflow.events.account.AccountRefundCreditedData;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Creates the causally linked Account outcome for a newly committed credit. */
public final class AccountRefundCreditedEventFactory {

    public static final String PRODUCER = "account-ledger-service";

    public EventEnvelope<AccountRefundCreditedData> credited(
            UUID eventId,
            EventEnvelope<AccountRefundCreditRequestedData> cause,
            RefundCreditResult result,
            Instant occurredAt) {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(cause, "cause");
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(occurredAt, "occurredAt");
        if (result.duplicate()) {
            throw new AccountInvariantViolationException(
                    "duplicate credit must not create another outcome event");
        }
        boolean commandMatches = AccountEvents.REFUND_CREDIT_REQUESTED.name().equals(cause.eventType())
                && AccountEvents.REFUND_CREDIT_REQUESTED.version() == cause.eventVersion()
                && AccountEvents.AGGREGATE_TYPE.equals(cause.aggregateType())
                && cause.data().paymentId().toString().equals(cause.aggregateId());
        AccountRefundCreditRequestedData command = cause.data();
        AccountRefundCreditedData outcome = result.eventData();
        boolean outcomeMatches = command.refundId().equals(outcome.refundId())
                && command.paymentId().equals(outcome.paymentId())
                && command.accountId().equals(outcome.accountId())
                && command.journalId().equals(outcome.journalId())
                && command.amount().compareTo(outcome.amount()) == 0
                && command.currency().equals(outcome.currency());
        if (!commandMatches || !outcomeMatches) {
            throw new AccountInvariantViolationException(
                    "refund-credited outcome does not match credit command");
        }
        return EventEnvelope.causedBy(
                eventId,
                AccountEvents.REFUND_CREDITED,
                cause.aggregateId(),
                cause,
                PRODUCER,
                occurredAt,
                outcome);
    }
}
