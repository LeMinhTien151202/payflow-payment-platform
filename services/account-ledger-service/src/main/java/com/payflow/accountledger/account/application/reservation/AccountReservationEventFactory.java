package com.payflow.accountledger.account.application.reservation;

import com.payflow.accountledger.account.domain.exception.AccountInvariantViolationException;
import com.payflow.events.EventEnvelope;
import com.payflow.events.account.AccountEvents;
import com.payflow.events.account.AccountFundsReservationFailedData;
import com.payflow.events.account.AccountFundsReservedData;
import com.payflow.events.account.AccountReserveRequestedData;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Maps a committed Account reserve result to its versioned, causally linked outcome envelope. */
public final class AccountReservationEventFactory {

    public static final String PRODUCER = "account-ledger-service";

    public EventEnvelope<AccountFundsReservedData> reserved(
            UUID eventId,
            EventEnvelope<AccountReserveRequestedData> cause,
            FundsReservedResult result,
            Instant occurredAt) {
        requireCommon(eventId, cause, occurredAt);
        Objects.requireNonNull(result, "result");
        if (result.duplicate()) {
            throw new AccountInvariantViolationException(
                    "duplicate reserve result must not create another outcome event");
        }
        requireMatchingOutcome(cause.data(), result.eventData());
        return EventEnvelope.causedBy(
                eventId,
                AccountEvents.FUNDS_RESERVED,
                cause.aggregateId(),
                cause,
                PRODUCER,
                occurredAt,
                result.eventData());
    }

    public EventEnvelope<AccountFundsReservationFailedData> failed(
            UUID eventId,
            EventEnvelope<AccountReserveRequestedData> cause,
            FundsReservationFailedResult result,
            Instant occurredAt) {
        requireCommon(eventId, cause, occurredAt);
        Objects.requireNonNull(result, "result");
        requireMatchingOutcome(cause.data(), result.eventData());
        return EventEnvelope.causedBy(
                eventId,
                AccountEvents.FUNDS_RESERVATION_FAILED,
                cause.aggregateId(),
                cause,
                PRODUCER,
                occurredAt,
                result.eventData());
    }

    private static void requireCommon(
            UUID eventId,
            EventEnvelope<AccountReserveRequestedData> cause,
            Instant occurredAt) {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(cause, "cause");
        Objects.requireNonNull(occurredAt, "occurredAt");
        if (!AccountEvents.RESERVE_REQUESTED.name().equals(cause.eventType())
                || AccountEvents.RESERVE_REQUESTED.version() != cause.eventVersion()
                || !AccountEvents.AGGREGATE_TYPE.equals(cause.aggregateType())) {
            throw new AccountInvariantViolationException(
                    "reservation outcome must be caused by account.reserve.requested v1");
        }
        if (!cause.data().paymentId().toString().equals(cause.aggregateId())) {
            throw new AccountInvariantViolationException(
                    "reserve command aggregateId does not match paymentId");
        }
        // Causation defines logical order. Comparing wall clocks from Payment and Account would make
        // harmless clock skew reject a valid outcome.
    }

    private static void requireMatchingOutcome(
            AccountReserveRequestedData command, AccountFundsReservedData outcome) {
        if (!command.paymentId().equals(outcome.paymentId())
                || !command.accountId().equals(outcome.accountId())
                || command.amount().compareTo(outcome.amount()) != 0
                || !command.currency().equals(outcome.currency())) {
            throw new AccountInvariantViolationException(
                    "funds-reserved outcome does not match reserve command");
        }
    }

    private static void requireMatchingOutcome(
            AccountReserveRequestedData command, AccountFundsReservationFailedData outcome) {
        if (!command.paymentId().equals(outcome.paymentId())
                || !command.accountId().equals(outcome.accountId())) {
            throw new AccountInvariantViolationException(
                    "reservation-failed outcome does not match reserve command");
        }
    }
}
