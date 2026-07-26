package com.payflow.events.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PaymentCreatedDataTest {

    private static final UUID ID = UUID.fromString("c73e17b5-aaca-48da-9ed5-bb0937499f01");
    private static final Instant CREATED_AT = Instant.parse("2026-07-24T03:00:00Z");

    private static PaymentCreatedData withAmount(BigDecimal amount) {
        return new PaymentCreatedData(ID, ID, ID, ID, amount, "VND", CREATED_AT);
    }

    @Test
    @DisplayName("an integral amount is widened to scale 4 so the wire format is deterministic")
    void widensToScaleFour() {
        assertThat(withAmount(new BigDecimal("500000")).amount())
                .isEqualTo(new BigDecimal("500000.0000"));
    }

    @Test
    @DisplayName("an amount already at scale 4 is untouched")
    void keepsScaleFour() {
        assertThat(withAmount(new BigDecimal("1234.5678")).amount().scale()).isEqualTo(4);
    }

    @Test
    @DisplayName("scale 5 is rejected rather than rounded: rounding policy is not this record's call")
    void rejectsScaleBeyondFour() {
        assertThatThrownBy(() -> withAmount(new BigDecimal("1.23456")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("scale");
    }

    @Test
    @DisplayName("zero is rejected")
    void rejectsZero() {
        assertThatThrownBy(() -> withAmount(BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive");
    }

    @Test
    @DisplayName("a negative amount is rejected at the event boundary")
    void rejectsNegative() {
        assertThatThrownBy(() -> withAmount(new BigDecimal("-1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive");
    }

    @Test
    @DisplayName("a lowercase currency is rejected: the wire value is uppercase ISO-4217")
    void rejectsLowercaseCurrency() {
        assertThatThrownBy(
                        () ->
                                new PaymentCreatedData(
                                        ID, ID, ID, ID, new BigDecimal("1"), "vnd", CREATED_AT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ISO-4217");
    }

    @Test
    @DisplayName("a currency that is not three characters is rejected")
    void rejectsWrongLengthCurrency() {
        assertThatThrownBy(
                        () ->
                                new PaymentCreatedData(
                                        ID, ID, ID, ID, new BigDecimal("1"), "VN", CREATED_AT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("3-letter");
    }

    @Test
    @DisplayName("every identifier is required")
    void rejectsMissingIdentifiers() {
        assertThatThrownBy(
                        () ->
                                new PaymentCreatedData(
                                        null, ID, ID, ID, new BigDecimal("1"), "VND", CREATED_AT))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("paymentId");

        assertThatThrownBy(
                        () ->
                                new PaymentCreatedData(
                                        ID, ID, ID, null, new BigDecimal("1"), "VND", CREATED_AT))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("sourceAccountId");
    }
}
