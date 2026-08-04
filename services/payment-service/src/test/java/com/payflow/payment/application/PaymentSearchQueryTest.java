package com.payflow.payment.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PaymentSearchQueryTest {

    private static final UUID MERCHANT_ID = UUID.randomUUID();

    @Test
    @DisplayName("uses an inclusive lower and exclusive upper time range")
    void acceptsValidHalfOpenRange() {
        PaymentSearchQuery query = new PaymentSearchQuery(
                MERCHANT_ID,
                null,
                Instant.parse("2026-07-01T00:00:00Z"),
                Instant.parse("2026-08-01T00:00:00Z"),
                2,
                20);

        assertThat(query.offset()).isEqualTo(40);
    }

    @Test
    @DisplayName("rejects an empty or reversed range")
    void rejectsInvalidRange() {
        Instant boundary = Instant.parse("2026-07-01T00:00:00Z");

        assertThatThrownBy(
                        () -> new PaymentSearchQuery(MERCHANT_ID, null, boundary, boundary, 0, 20))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("from must be before to");
    }

    @Test
    @DisplayName("bounds page size and the JPA integer offset")
    void boundsPagination() {
        assertThatThrownBy(() -> new PaymentSearchQuery(MERCHANT_ID, null, null, null, 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PaymentSearchQuery(MERCHANT_ID, null, null, null, 0, 101))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () -> new PaymentSearchQuery(
                                MERCHANT_ID, null, null, null, Integer.MAX_VALUE, 2))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("offset");
    }
}
