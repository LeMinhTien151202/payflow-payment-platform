package com.payflow.accountledger.account.application.reservation;

import com.payflow.accountledger.account.domain.exception.AccountInvariantViolationException;
import com.payflow.events.EventEnvelope;
import com.payflow.events.account.AccountEvents;
import com.payflow.events.account.AccountFundsReleasedData;
import com.payflow.events.account.AccountReleaseRequestedData;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Creates the causally linked release outcome after Account's local transaction commits. */
public final class AccountReleaseEventFactory {

    public EventEnvelope<AccountFundsReleasedData> released(
            UUID eventId,
            EventEnvelope<AccountReleaseRequestedData> cause,
            FundsReleasedResult result,
            Instant occurredAt) {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(cause, "cause");
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(occurredAt, "occurredAt");
        if (result.duplicate()) {
            throw new AccountInvariantViolationException(
                    "duplicate release must not create another outcome event");
        }
        if (!AccountEvents.RELEASE_REQUESTED.name().equals(cause.eventType())
                || AccountEvents.RELEASE_REQUESTED.version() != cause.eventVersion()
                || !AccountEvents.AGGREGATE_TYPE.equals(cause.aggregateType())) {
            throw new AccountInvariantViolationException(
                    "release outcome must be caused by account.release.requested v1");
        }
        AccountReleaseRequestedData command = cause.data();
        AccountFundsReleasedData outcome = result.eventData();
        boolean matching = command.paymentId().equals(outcome.paymentId())
                && command.accountId().equals(outcome.accountId())
                && command.reservationId().equals(outcome.reservationId())
                && command.amount().compareTo(outcome.amount()) == 0
                && command.currency().equals(outcome.currency())
                && command.reasonCode().equals(outcome.reasonCode())
                && command.paymentId().toString().equals(cause.aggregateId());
        if (!matching) {
            throw new AccountInvariantViolationException(
                    "funds-released outcome does not match release command");
        }
        return EventEnvelope.causedBy(
                eventId,
                AccountEvents.FUNDS_RELEASED,
                cause.aggregateId(),
                cause,
                AccountReservationEventFactory.PRODUCER,
                occurredAt,
                outcome);
    }
}
