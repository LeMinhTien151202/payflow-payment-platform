package com.payflow.payment.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.payflow.payment.domain.exception.UnsupportedCurrencyException;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MoneyTest {

    @Test
    @DisplayName("scale is normalised to 4, so equal amounts are equal objects")
    void normalisesScale() {
        assertThat(Money.of("500000", "VND")).isEqualTo(Money.of("500000.0000", "VND"));
        assertThat(Money.of("500000", "VND").amount().scale()).isEqualTo(4);
    }

    /**
     * The reason scale is normalised rather than left alone. Without it, a hash-based lookup keyed on
     * Money would miss for two amounts that are the same money.
     */
    @Test
    @DisplayName("two amounts equal in value hash the same")
    void equalAmountsHashTheSame() {
        assertThat(Money.of("100", "VND")).hasSameHashCodeAs(Money.of("100.00", "VND"));
    }

    @Test
    @DisplayName("scale beyond 4 is rejected, not rounded")
    void rejectsExtraScale() {
        assertThatThrownBy(() -> Money.of("1.23456", "VND"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("scale");
    }

    @Test
    @DisplayName("a negative amount is rejected")
    void rejectsNegative() {
        assertThatThrownBy(() -> Money.of("-0.0001", "VND"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("negative");
    }

    @Test
    @DisplayName("zero is a valid amount but is not positive")
    void allowsZero() {
        assertThat(Money.zero("VND").isPositive()).isFalse();
        assertThat(Money.of("0.0001", "VND").isPositive()).isTrue();
    }

    @Test
    @DisplayName("an unsupported currency is a domain rejection, not a validation error")
    void rejectsUnsupportedCurrency() {
        assertThatThrownBy(() -> Money.of("1", "USD"))
                .isInstanceOf(UnsupportedCurrencyException.class);
    }

    @Test
    @DisplayName("a malformed currency code is rejected before the supported-set check")
    void rejectsMalformedCurrency() {
        assertThatThrownBy(() -> Money.of("1", "vnd"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ISO-4217");
        assertThatThrownBy(() -> Money.of("1", "VN"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ISO-4217");
    }

    @Test
    @DisplayName("isGreaterThan compares by value, not by scale")
    void comparesByValue() {
        assertThat(Money.of("100.0000", "VND").isGreaterThan(Money.of("100", "VND"))).isFalse();
        assertThat(Money.of("100.0001", "VND").isGreaterThan(Money.of("100", "VND"))).isTrue();
    }

    @Test
    @DisplayName("comparing against null fails rather than returning a default")
    void refusesNullComparison() {
        assertThatThrownBy(() -> Money.of("100", "VND").isGreaterThan(null))
                .isInstanceOf(NullPointerException.class);
    }

    // The cross-currency guard in isGreaterThan has no test, and cannot have one: VND is the only
    // supported currency, so no caller can build a Money that would trip it. It is unverified on
    // purpose rather than covered by a test that constructs an impossible object.

    @Test
    @DisplayName("toString uses plain notation so a large amount is readable in a log")
    void printsPlainNotation() {
        assertThat(Money.of("5000000000", "VND")).hasToString("5000000000.0000 VND");
    }

    @Test
    @DisplayName("a null amount or currency is rejected")
    void rejectsNulls() {
        assertThatThrownBy(() -> new Money(null, "VND")).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Money(BigDecimal.ONE, null))
                .isInstanceOf(NullPointerException.class);
    }
}
