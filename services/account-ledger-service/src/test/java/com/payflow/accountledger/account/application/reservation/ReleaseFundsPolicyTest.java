package com.payflow.accountledger.account.application.reservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.payflow.accountledger.account.domain.exception.AccountInvariantViolationException;
import com.payflow.accountledger.account.domain.model.Account;
import com.payflow.accountledger.account.domain.model.Money;
import com.payflow.accountledger.account.domain.model.Reservation;
import com.payflow.accountledger.account.domain.model.ReservationStatus;
import com.payflow.events.account.AccountReleaseRequestedData;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ReleaseFundsPolicyTest {

    private static final UUID PAYMENT_ID =
            UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
    private static final UUID ACCOUNT_ID =
            UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
    private static final UUID RESERVATION_ID =
            UUID.fromString("cccccccc-cccc-4ccc-8ccc-cccccccccccc");
    private static final Instant NOW = Instant.parse("2026-07-28T11:00:00Z");

    private final ReleaseFundsPolicy policy = new ReleaseFundsPolicy();

    @Test
    void releaseRestoresBalanceAndProducesStableOutcome() {
        Fixture fixture = fixture();

        FundsReleasedResult result = policy.release(
                fixture.account(), fixture.reservation(), command(), NOW.plusSeconds(2));

        assertThat(result.duplicate()).isFalse();
        assertThat(result.reservation().status()).isEqualTo(ReservationStatus.RELEASED);
        assertThat(result.eventData().reasonCode()).isEqualTo("LEDGER_RETRY_EXHAUSTED");
        assertThat(fixture.account().availableBalance().amount())
                .isEqualByComparingTo("1000000");
        assertThat(fixture.account().reservedBalance().amount()).isZero();
    }

    @Test
    void duplicateReleaseIsANoOpAndCannotRestoreTwice() {
        Fixture fixture = fixture();
        policy.release(fixture.account(), fixture.reservation(), command(), NOW.plusSeconds(2));

        FundsReleasedResult duplicate = policy.release(
                fixture.account(), fixture.reservation(), command(), NOW.plusSeconds(3));

        assertThat(duplicate.duplicate()).isTrue();
        assertThat(fixture.account().availableBalance().amount())
                .isEqualByComparingTo("1000000");
        assertThat(fixture.account().reservedBalance().amount()).isZero();
    }

    @Test
    void capturedReservationCanNeverBeReleasedByCompensation() {
        Fixture fixture = fixture();
        fixture.account().capture(fixture.reservation(), NOW.plusSeconds(2));

        assertThatThrownBy(() -> policy.release(
                        fixture.account(),
                        fixture.reservation(),
                        command(),
                        NOW.plusSeconds(3)))
                .isInstanceOf(AccountInvariantViolationException.class)
                .hasMessageContaining("CAPTURED");
        assertThat(fixture.account().availableBalance().amount())
                .isEqualByComparingTo("500000");
        assertThat(fixture.account().reservedBalance().amount()).isZero();
    }

    @Test
    void mismatchedIntentCannotMutateReservationOrBalance() {
        Fixture fixture = fixture();
        AccountReleaseRequestedData wrong = new AccountReleaseRequestedData(
                PAYMENT_ID,
                ACCOUNT_ID,
                UUID.randomUUID(),
                new BigDecimal("500000"),
                "VND",
                "LEDGER_RETRY_EXHAUSTED");

        assertThatThrownBy(() -> policy.release(
                        fixture.account(),
                        fixture.reservation(),
                        wrong,
                        NOW.plusSeconds(2)))
                .isInstanceOf(AccountInvariantViolationException.class)
                .hasMessageContaining("does not match");
        assertThat(fixture.reservation().status()).isEqualTo(ReservationStatus.ACTIVE);
        assertThat(fixture.account().availableBalance().amount())
                .isEqualByComparingTo("500000");
        assertThat(fixture.account().reservedBalance().amount())
                .isEqualByComparingTo("500000");
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

    private static AccountReleaseRequestedData command() {
        return new AccountReleaseRequestedData(
                PAYMENT_ID,
                ACCOUNT_ID,
                RESERVATION_ID,
                new BigDecimal("500000"),
                "VND",
                "LEDGER_RETRY_EXHAUSTED");
    }

    private record Fixture(Account account, Reservation reservation) {}
}

