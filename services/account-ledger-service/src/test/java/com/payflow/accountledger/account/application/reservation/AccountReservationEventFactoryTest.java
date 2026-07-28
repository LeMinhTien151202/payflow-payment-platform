package com.payflow.accountledger.account.application.reservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.payflow.accountledger.account.domain.exception.AccountInvariantViolationException;
import com.payflow.accountledger.account.domain.model.Account;
import com.payflow.accountledger.account.domain.model.Money;
import com.payflow.events.EventEnvelope;
import com.payflow.events.EventType;
import com.payflow.events.account.AccountEvents;
import com.payflow.events.account.AccountFundsReservationFailedData;
import com.payflow.events.account.AccountFundsReservedData;
import com.payflow.events.account.AccountReserveRequestedData;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AccountReservationEventFactoryTest {

    private static final UUID COMMAND_EVENT_ID =
            UUID.fromString("11734b31-8e75-4570-bdc6-979fa02ab441");
    private static final UUID OUTCOME_EVENT_ID =
            UUID.fromString("21734b31-8e75-4570-bdc6-979fa02ab442");
    private static final UUID PAYMENT_ID =
            UUID.fromString("c73e17b5-aaca-48da-9ed5-bb0937499f01");
    private static final UUID ACCOUNT_ID =
            UUID.fromString("039bedb6-b2d6-47df-aa25-2035e39136a3");
    private static final UUID RESERVATION_ID =
            UUID.fromString("41734b31-8e75-4570-bdc6-979fa02ab447");
    private static final Instant REQUESTED_AT = Instant.parse("2026-07-28T10:00:00Z");
    private static final Instant RESERVED_AT = REQUESTED_AT.plusSeconds(1);

    private final ReserveFundsPolicy policy = new ReserveFundsPolicy();
    private final AccountReservationEventFactory factory =
            new AccountReservationEventFactory();

    @Test
    void createsFundsReservedWithPaymentKeyCorrelationAndCausation() {
        FundsReservedResult result = (FundsReservedResult) policy.reserve(
                account("1000000"), null, command().data(), RESERVATION_ID, RESERVED_AT);

        EventEnvelope<AccountFundsReservedData> outcome =
                factory.reserved(OUTCOME_EVENT_ID, command(), result, RESERVED_AT);

        assertThat(outcome.eventType()).isEqualTo("account.funds-reserved");
        assertThat(outcome.eventVersion()).isEqualTo(1);
        assertThat(outcome.aggregateId()).isEqualTo(PAYMENT_ID.toString());
        assertThat(outcome.correlationId()).isEqualTo(command().correlationId());
        assertThat(outcome.causationId()).isEqualTo(COMMAND_EVENT_ID.toString());
        assertThat(outcome.producer()).isEqualTo("account-ledger-service");
    }

    @Test
    void createsStableFailureWithoutLeakingExceptionDetails() {
        FundsReservationFailedResult result = (FundsReservationFailedResult) policy.reserve(
                account("100"), null, command().data(), RESERVATION_ID, RESERVED_AT);

        EventEnvelope<AccountFundsReservationFailedData> outcome =
                factory.failed(OUTCOME_EVENT_ID, command(), result, RESERVED_AT);

        assertThat(outcome.eventType()).isEqualTo("account.funds-reservation-failed");
        assertThat(outcome.data().reasonCode()).isEqualTo("ACCOUNT_INSUFFICIENT_FUNDS");
    }

    @Test
    void refusesWrongCauseContractAndAggregateKey() {
        FundsReservationFailedResult result = new FundsReservationFailedResult(
                new AccountFundsReservationFailedData(
                        PAYMENT_ID, ACCOUNT_ID, "ACCOUNT_FROZEN"));
        var wrongType = EventEnvelope.of(
                COMMAND_EVENT_ID,
                new EventType("account.other", 1, "PAYMENT"),
                PAYMENT_ID.toString(),
                "correlation-1",
                "payment-service",
                REQUESTED_AT,
                command().data());
        var wrongKey = new EventEnvelope<>(
                COMMAND_EVENT_ID,
                AccountEvents.RESERVE_REQUESTED.name(),
                1,
                "PAYMENT",
                UUID.randomUUID().toString(),
                "correlation-1",
                null,
                "payment-service",
                REQUESTED_AT,
                command().data());

        assertThatThrownBy(() -> factory.failed(
                        OUTCOME_EVENT_ID, wrongType, result, RESERVED_AT))
                .isInstanceOf(AccountInvariantViolationException.class)
                .hasMessageContaining("reserve.requested");
        assertThatThrownBy(() -> factory.failed(
                        OUTCOME_EVENT_ID, wrongKey, result, RESERVED_AT))
                .isInstanceOf(AccountInvariantViolationException.class)
                .hasMessageContaining("aggregateId");
    }

    @Test
    void duplicateBusinessIntentCannotCreateAnotherOutcomeEvent() {
        Account account = account("1000000");
        FundsReservedResult first = (FundsReservedResult) policy.reserve(
                account, null, command().data(), RESERVATION_ID, RESERVED_AT);
        FundsReservedResult duplicate = (FundsReservedResult) policy.reserve(
                account,
                first.reservation(),
                command().data(),
                UUID.randomUUID(),
                RESERVED_AT.plusSeconds(1));

        assertThatThrownBy(() -> factory.reserved(
                        OUTCOME_EVENT_ID, command(), duplicate, RESERVED_AT.plusSeconds(1)))
                .isInstanceOf(AccountInvariantViolationException.class)
                .hasMessageContaining("must not create another");
    }

    private static EventEnvelope<AccountReserveRequestedData> command() {
        return EventEnvelope.of(
                COMMAND_EVENT_ID,
                AccountEvents.RESERVE_REQUESTED,
                PAYMENT_ID.toString(),
                "correlation-1",
                "payment-service",
                REQUESTED_AT,
                new AccountReserveRequestedData(
                        PAYMENT_ID,
                        ACCOUNT_ID,
                        new BigDecimal("500000"),
                        "VND",
                        REQUESTED_AT.plusSeconds(900)));
    }

    private static Account account(String amount) {
        return Account.open(ACCOUNT_ID, new Money(new BigDecimal(amount), "VND"));
    }
}
