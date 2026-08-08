package com.payflow.account.application.reservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.payflow.account.domain.exception.AccountInvariantViolationException;
import com.payflow.account.domain.model.Account;
import com.payflow.account.domain.model.Money;
import com.payflow.account.domain.model.Reservation;
import com.payflow.events.account.AccountReserveRequestedData;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ReserveFundsPolicyTest {

    private static final UUID PAYMENT_ID =
            UUID.fromString("c73e17b5-aaca-48da-9ed5-bb0937499f01");
    private static final UUID ACCOUNT_ID =
            UUID.fromString("039bedb6-b2d6-47df-aa25-2035e39136a3");
    private static final UUID RESERVATION_ID =
            UUID.fromString("41734b31-8e75-4570-bdc6-979fa02ab447");
    private static final Instant NOW = Instant.parse("2026-07-28T10:00:00Z");
    private static final Instant EXPIRES_AT = NOW.plusSeconds(900);

    private final ReserveFundsPolicy policy = new ReserveFundsPolicy();

    @Test
    void reservesOnceAndReturnsVersionedEventData() {
        Account account = account("1000000");

        ReserveFundsResult result =
                policy.reserve(account, null, command("500000"), RESERVATION_ID, NOW);

        assertThat(result).isInstanceOf(FundsReservedResult.class);
        FundsReservedResult reserved = (FundsReservedResult) result;
        assertThat(reserved.duplicate()).isFalse();
        assertThat(reserved.eventData().paymentId()).isEqualTo(PAYMENT_ID);
        assertThat(reserved.eventData().reservationId()).isEqualTo(RESERVATION_ID);
        assertThat(reserved.reservation().expiresAt()).isEqualTo(EXPIRES_AT);
        assertThat(account.availableBalance().amount()).isEqualByComparingTo("500000");
        assertThat(account.reservedBalance().amount()).isEqualByComparingTo("500000");
    }

    @Test
    void duplicateSameIntentReturnsExistingReservationWithoutMovingMoneyAgain() {
        Account account = account("1000000");
        FundsReservedResult first = (FundsReservedResult)
                policy.reserve(account, null, command("500000"), RESERVATION_ID, NOW);

        FundsReservedResult duplicate = (FundsReservedResult) policy.reserve(
                account,
                first.reservation(),
                command("500000.0000"),
                UUID.randomUUID(),
                NOW.plusSeconds(1));

        assertThat(duplicate.duplicate()).isTrue();
        assertThat(duplicate.reservation()).isSameAs(first.reservation());
        assertThat(duplicate.eventData()).isEqualTo(first.eventData());
        assertThat(account.availableBalance().amount()).isEqualByComparingTo("500000");
        assertThat(account.reservedBalance().amount()).isEqualByComparingTo("500000");
    }

    @Test
    void duplicateDifferentIntentIsAConflictWithoutBalanceMutation() {
        Account account = account("1000000");
        Reservation existing = ((FundsReservedResult)
                        policy.reserve(account, null, command("500000"), RESERVATION_ID, NOW))
                .reservation();

        assertThatThrownBy(() -> policy.reserve(
                        account,
                        existing,
                        command("400000"),
                        UUID.randomUUID(),
                        NOW.plusSeconds(1)))
                .isInstanceOf(AccountInvariantViolationException.class)
                .hasMessageContaining("duplicate reserve intent");
        assertThat(account.availableBalance().amount()).isEqualByComparingTo("500000");
        assertThat(account.reservedBalance().amount()).isEqualByComparingTo("500000");
    }

    @Test
    void insufficientFundsReturnsStableFailureAndLeavesBalanceUntouched() {
        Account account = account("100000");

        FundsReservationFailedResult result = (FundsReservationFailedResult)
                policy.reserve(account, null, command("500000"), RESERVATION_ID, NOW);

        assertThat(result.eventData().reasonCode())
                .isEqualTo(ReserveFundsPolicy.INSUFFICIENT_FUNDS);
        assertThat(account.availableBalance().amount()).isEqualByComparingTo("100000");
        assertThat(account.reservedBalance().amount()).isZero();
    }

    @Test
    void frozenAndExpiredCommandsReturnStableFailures() {
        Account frozen = account("1000000");
        frozen.freeze();

        FundsReservationFailedResult frozenResult = (FundsReservationFailedResult)
                policy.reserve(frozen, null, command("1"), RESERVATION_ID, NOW);
        FundsReservationFailedResult expiredResult = (FundsReservationFailedResult)
                policy.reserve(
                        account("1000000"),
                        null,
                        new AccountReserveRequestedData(
                                PAYMENT_ID,
                                ACCOUNT_ID,
                                BigDecimal.ONE,
                                "VND",
                                NOW),
                        RESERVATION_ID,
                        NOW);

        assertThat(frozenResult.eventData().reasonCode()).isEqualTo("ACCOUNT_FROZEN");
        assertThat(expiredResult.eventData().reasonCode())
                .isEqualTo("ACCOUNT_RESERVATION_DEADLINE_EXPIRED");
    }

    @Test
    void rejectsCommandForAnotherAccountBeforeChangingBalance() {
        Account account = account("1000000");
        var other = new AccountReserveRequestedData(
                PAYMENT_ID, UUID.randomUUID(), BigDecimal.ONE, "VND", EXPIRES_AT);

        assertThatThrownBy(
                        () -> policy.reserve(account, null, other, RESERVATION_ID, NOW))
                .isInstanceOf(AccountInvariantViolationException.class)
                .hasMessageContaining("another account");
        assertThat(account.availableBalance().amount()).isEqualByComparingTo("1000000");
    }

    private static Account account(String balance) {
        return Account.open(ACCOUNT_ID, new Money(new BigDecimal(balance), "VND"));
    }

    private static AccountReserveRequestedData command(String amount) {
        return new AccountReserveRequestedData(
                PAYMENT_ID, ACCOUNT_ID, new BigDecimal(amount), "VND", EXPIRES_AT);
    }
}
