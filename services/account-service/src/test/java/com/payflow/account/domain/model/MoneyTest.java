package com.payflow.account.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.payflow.account.domain.exception.AccountInvariantViolationException;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class MoneyTest {

    @Test
    void storesTheDatabaseScaleExactly() {
        Money money = new Money(new BigDecimal("10"), "VND");

        assertThat(money.amount()).isEqualTo(new BigDecimal("10.0000"));
    }

    @Test
    void rejectsNegativeExtraScaleAndNonCanonicalCurrency() {
        assertThatThrownBy(() -> new Money(new BigDecimal("-0.0001"), "VND"))
                .isInstanceOf(AccountInvariantViolationException.class);
        assertThatThrownBy(() -> new Money(new BigDecimal("1.00001"), "VND"))
                .isInstanceOf(AccountInvariantViolationException.class);
        assertThatThrownBy(() -> new Money(BigDecimal.ONE, "vnd"))
                .isInstanceOf(AccountInvariantViolationException.class);
    }

    @Test
    void zeroBalanceIsValidButZeroOperationIsNot() {
        Money zero = Money.zero("VND");

        assertThat(zero.amount()).isZero();
        assertThatThrownBy(zero::requirePositive)
                .isInstanceOf(AccountInvariantViolationException.class);
    }
}
