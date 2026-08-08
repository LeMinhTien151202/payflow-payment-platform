package com.payflow.account.application.reservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.payflow.account.domain.exception.AccountInvariantViolationException;
import com.payflow.account.domain.model.Account;
import com.payflow.account.domain.model.Money;
import com.payflow.account.domain.model.Reservation;
import com.payflow.events.EventEnvelope;
import com.payflow.events.account.AccountEvents;
import com.payflow.events.account.AccountFundsReleasedData;
import com.payflow.events.account.AccountReleaseRequestedData;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AccountReleaseEventFactoryTest {

    private static final UUID PAYMENT_ID =
            UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
    private static final UUID ACCOUNT_ID =
            UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
    private static final UUID RESERVATION_ID =
            UUID.fromString("cccccccc-cccc-4ccc-8ccc-cccccccccccc");
    private static final UUID COMMAND_ID =
            UUID.fromString("dddddddd-dddd-4ddd-8ddd-dddddddddddd");
    private static final Instant NOW = Instant.parse("2026-07-28T11:00:00Z");

    private final ReleaseFundsPolicy policy = new ReleaseFundsPolicy();
    private final AccountReleaseEventFactory factory = new AccountReleaseEventFactory();

    @Test
    void createsFundsReleasedWithOriginalCorrelationAndCausation() {
        Fixture fixture = fixture();
        FundsReleasedResult result = policy.release(
                fixture.account(), fixture.reservation(), command().data(), NOW.plusSeconds(2));

        EventEnvelope<AccountFundsReleasedData> event = factory.released(
                UUID.randomUUID(), command(), result, NOW.plusSeconds(2));

        assertThat(event.eventType()).isEqualTo("account.funds-released");
        assertThat(event.aggregateId()).isEqualTo(PAYMENT_ID.toString());
        assertThat(event.correlationId()).isEqualTo("correlation-1");
        assertThat(event.causationId()).isEqualTo(COMMAND_ID.toString());
    }

    @Test
    void duplicateBusinessReleaseCannotCreateSecondOutcome() {
        Fixture fixture = fixture();
        policy.release(fixture.account(), fixture.reservation(), command().data(), NOW.plusSeconds(2));
        FundsReleasedResult duplicate = policy.release(
                fixture.account(), fixture.reservation(), command().data(), NOW.plusSeconds(3));

        assertThatThrownBy(() -> factory.released(
                        UUID.randomUUID(), command(), duplicate, NOW.plusSeconds(3)))
                .isInstanceOf(AccountInvariantViolationException.class)
                .hasMessageContaining("must not create another");
    }

    private static EventEnvelope<AccountReleaseRequestedData> command() {
        return EventEnvelope.of(
                COMMAND_ID,
                AccountEvents.RELEASE_REQUESTED,
                PAYMENT_ID.toString(),
                "correlation-1",
                "payment-service",
                NOW.plusSeconds(1),
                new AccountReleaseRequestedData(
                        PAYMENT_ID,
                        ACCOUNT_ID,
                        RESERVATION_ID,
                        new BigDecimal("500000"),
                        "VND",
                        "LEDGER_RETRY_EXHAUSTED"));
    }

    private static Fixture fixture() {
        Account account = Account.open(
                ACCOUNT_ID, new Money(new BigDecimal("1000000"), "VND"));
        Reservation reservation = account.reserve(
                RESERVATION_ID,
                PAYMENT_ID,
                new Money(new BigDecimal("500000"), "VND"),
                NOW,
                NOW.plusSeconds(900));
        return new Fixture(account, reservation);
    }

    private record Fixture(Account account, Reservation reservation) {}
}
