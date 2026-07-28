package com.payflow.accountledger.account.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.payflow.accountledger.account.domain.exception.AccountInvariantViolationException;
import com.payflow.accountledger.account.domain.exception.InsufficientFundsException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AccountTest {

    private static final Instant NOW = Instant.parse("2026-07-26T00:00:00Z");
    private static final UUID ACCOUNT_ID = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
    private Account account;

    @BeforeEach
    void setUp() {
        account = Account.open(ACCOUNT_ID, money("1000000"));
    }

    @Test
    void reserveMovesTheSameAmountFromAvailableToReserved() {
        Reservation reservation = reserve("500000");

        assertThat(account.availableBalance().amount()).isEqualByComparingTo("500000");
        assertThat(account.reservedBalance().amount()).isEqualByComparingTo("500000");
        assertThat(reservation.status()).isEqualTo(ReservationStatus.ACTIVE);
        assertThat(reservation.amount().amount()).isEqualByComparingTo("500000");
    }

    @Test
    void insufficientFundsRejectsWithoutChangingEitherBalance() {
        assertThatThrownBy(() -> reserve("1000000.0001"))
                .isInstanceOf(InsufficientFundsException.class);

        assertThat(account.availableBalance().amount()).isEqualByComparingTo("1000000");
        assertThat(account.reservedBalance().amount()).isZero();
    }

    @Test
    void frozenAccountCannotCreateANewReservation() {
        account.freeze();

        assertThatThrownBy(() -> reserve("1"))
                .isInstanceOf(AccountInvariantViolationException.class)
                .hasMessageContaining("ACTIVE");
        assertThat(account.availableBalance().amount()).isEqualByComparingTo("1000000");
    }

    @Test
    void frozenAccountCanReleaseAnExistingReservationSoFundsAreNotTrapped() {
        Reservation reservation = reserve("500000");
        account.freeze();

        assertThat(account.release(reservation, NOW.plusSeconds(1))).isTrue();
        assertThat(account.availableBalance().amount()).isEqualByComparingTo("1000000");
        assertThat(account.reservedBalance().amount()).isZero();
    }

    @Test
    void captureConsumesReservedFundsAndDuplicateCaptureIsANoOp() {
        Reservation reservation = reserve("500000");

        assertThat(account.capture(reservation, NOW.plusSeconds(1))).isTrue();
        assertThat(account.capture(reservation, NOW.plusSeconds(2))).isFalse();

        assertThat(reservation.status()).isEqualTo(ReservationStatus.CAPTURED);
        assertThat(account.availableBalance().amount()).isEqualByComparingTo("500000");
        assertThat(account.reservedBalance().amount()).isZero();
    }

    @Test
    void releaseRestoresAvailableFundsAndDuplicateReleaseIsANoOp() {
        Reservation reservation = reserve("500000");

        assertThat(account.release(reservation, NOW.plusSeconds(1))).isTrue();
        assertThat(account.release(reservation, NOW.plusSeconds(2))).isFalse();

        assertThat(reservation.status()).isEqualTo(ReservationStatus.RELEASED);
        assertThat(account.availableBalance().amount()).isEqualByComparingTo("1000000");
        assertThat(account.reservedBalance().amount()).isZero();
    }

    @Test
    void lateReleaseCannotUndoACapturedReservation() {
        Reservation reservation = reserve("500000");
        account.capture(reservation, NOW.plusSeconds(1));

        assertThatThrownBy(() -> account.release(reservation, NOW.plusSeconds(2)))
                .isInstanceOf(AccountInvariantViolationException.class)
                .hasMessageContaining("CAPTURED")
                .hasMessageContaining("RELEASED");
        assertThat(account.availableBalance().amount()).isEqualByComparingTo("500000");
        assertThat(account.reservedBalance().amount()).isZero();
    }

    @Test
    void expiryReturnsFundsAndCannotLaterBeCaptured() {
        Reservation reservation = reserve("250000");
        account.expire(reservation, NOW.plusSeconds(60));

        assertThatThrownBy(() -> account.capture(reservation, NOW.plusSeconds(61)))
                .isInstanceOf(AccountInvariantViolationException.class)
                .hasMessageContaining("EXPIRED");
        assertThat(account.availableBalance().amount()).isEqualByComparingTo("1000000");
        assertThat(account.reservedBalance().amount()).isZero();
    }

    @Test
    void accountRejectsAnotherCurrency() {
        assertThatThrownBy(
                        () ->
                                account.reserve(
                                        UUID.randomUUID(),
                                        UUID.randomUUID(),
                                        new Money(new BigDecimal("1"), "USD"),
                                        NOW,
                                        NOW.plusSeconds(60)))
                .isInstanceOf(AccountInvariantViolationException.class)
                .hasMessageContaining("currency");
    }

    @Test
    void accountWithReservedFundsCannotBeClosed() {
        reserve("1");

        assertThatThrownBy(account::close)
                .isInstanceOf(AccountInvariantViolationException.class)
                .hasMessageContaining("reserved");
    }

    @Test
    void reservationExpiryMustBeAfterCreation() {
        assertThatThrownBy(() -> account.reserve(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        money("1"),
                        NOW,
                        NOW))
                .isInstanceOf(AccountInvariantViolationException.class)
                .hasMessageContaining("expiry");
        assertThat(account.availableBalance().amount()).isEqualByComparingTo("1000000");
        assertThat(account.reservedBalance().amount()).isZero();
    }

    @Test
    void cannotExpireBeforeDeadlineOrCaptureAtDeadline() {
        Reservation reservation = reserve("100");

        assertThatThrownBy(() -> account.expire(reservation, NOW.plusSeconds(59)))
                .isInstanceOf(AccountInvariantViolationException.class)
                .hasMessageContaining("before its deadline");
        assertThatThrownBy(() -> account.capture(reservation, NOW.plusSeconds(60)))
                .isInstanceOf(AccountInvariantViolationException.class)
                .hasMessageContaining("deadline");
        assertThat(reservation.status()).isEqualTo(ReservationStatus.ACTIVE);
        assertThat(account.reservedBalance().amount()).isEqualByComparingTo("100");
    }

    @Test
    void cannotInspectOrExpireAReservationOwnedByAnotherAccount() {
        Account otherAccount = Account.open(UUID.randomUUID(), money("1000"));
        Reservation otherReservation = otherAccount.reserve(
                UUID.randomUUID(),
                UUID.randomUUID(),
                money("100"),
                NOW,
                NOW.plusSeconds(60));

        assertThatThrownBy(() -> account.expire(otherReservation, NOW.plusSeconds(1)))
                .isInstanceOf(AccountInvariantViolationException.class)
                .hasMessageContaining("another account");
        assertThat(otherReservation.status()).isEqualTo(ReservationStatus.ACTIVE);
        assertThat(account.availableBalance().amount()).isEqualByComparingTo("1000000");
        assertThat(account.reservedBalance().amount()).isZero();
    }

    private Reservation reserve(String amount) {
        return account.reserve(
                UUID.randomUUID(),
                UUID.randomUUID(),
                money(amount),
                NOW,
                NOW.plusSeconds(60));
    }

    private static Money money(String amount) {
        return new Money(new BigDecimal(amount), "VND");
    }
}
