package com.payflow.payment.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The bounds here exist so that no oversized value ever reaches a column that would either truncate it
 * or accept it unbounded. A {@code VARCHAR(100)} overflow would surface as a database error in the
 * middle of the payment transaction — the same transaction that carries the outbox insert — so failing
 * this early is what keeps a merchant's long reference string from rolling back their payment.
 */
class PaymentIntakeTest {

    private static final Instant CREATED_AT = Instant.parse("2026-07-24T03:00:00Z");

    private static PaymentIntake intake(
            String merchantReference,
            String idempotencyKey,
            String description,
            Map<String, String> metadata) {

        return new PaymentIntake(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                merchantReference,
                idempotencyKey,
                Money.of("100", "VND"),
                description,
                metadata,
                CREATED_AT);
    }

    private static PaymentIntake valid() {
        return intake("ORDER-1", "key-1", "note", Map.of("orderId", "ORDER-1"));
    }

    @Test
    @DisplayName("a valid intake keeps every value it was given")
    void keepsValidValues() {
        PaymentIntake intake = valid();

        assertThat(intake.merchantReference()).isEqualTo("ORDER-1");
        assertThat(intake.idempotencyKey()).isEqualTo("key-1");
        assertThat(intake.description()).isEqualTo("note");
        assertThat(intake.metadata()).containsExactly(Map.entry("orderId", "ORDER-1"));
        assertThat(intake.createdAt()).isEqualTo(CREATED_AT);
    }

    @Test
    @DisplayName("a blank merchant reference is rejected")
    void rejectsBlankMerchantReference() {
        assertThatThrownBy(() -> intake("   ", "key-1", null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("merchantReference");
    }

    @Test
    @DisplayName("a merchant reference longer than the column is rejected")
    void rejectsOverlongMerchantReference() {
        String tooLong = "R".repeat(PaymentIntake.MAX_MERCHANT_REFERENCE_LENGTH + 1);

        assertThatThrownBy(() -> intake(tooLong, "key-1", null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("merchantReference");
    }

    @Test
    @DisplayName("a reference exactly at the column width is accepted")
    void acceptsMaximumLengthReference() {
        String atLimit = "R".repeat(PaymentIntake.MAX_MERCHANT_REFERENCE_LENGTH);

        assertThat(intake(atLimit, "key-1", null, null).merchantReference()).hasSize(100);
    }

    @Test
    @DisplayName("a blank or overlong idempotency key is rejected")
    void rejectsBadIdempotencyKey() {
        assertThatThrownBy(() -> intake("ORDER-1", "", null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("idempotencyKey");

        assertThatThrownBy(
                        () ->
                                intake(
                                        "ORDER-1",
                                        "K".repeat(PaymentIntake.MAX_IDEMPOTENCY_KEY_LENGTH + 1),
                                        null,
                                        null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("idempotencyKey");
    }

    @Test
    @DisplayName("a description longer than the column is rejected")
    void rejectsOverlongDescription() {
        String tooLong = "D".repeat(PaymentIntake.MAX_DESCRIPTION_LENGTH + 1);

        assertThatThrownBy(() -> intake("ORDER-1", "key-1", tooLong, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("description");
    }

    @Test
    @DisplayName("a missing description is allowed")
    void allowsNoDescription() {
        assertThat(intake("ORDER-1", "key-1", null, null).description()).isNull();
    }

    @Test
    @DisplayName("null and empty metadata both become an empty map, so callers never see null")
    void normalisesMissingMetadata() {
        assertThat(intake("ORDER-1", "key-1", null, null).metadata()).isEmpty();
        assertThat(intake("ORDER-1", "key-1", null, Map.of()).metadata()).isEmpty();
    }

    @Test
    @DisplayName("too many metadata entries are rejected")
    void rejectsTooManyMetadataEntries() {
        Map<String, String> tooMany = new HashMap<>();
        for (int i = 0; i <= PaymentIntake.MAX_METADATA_ENTRIES; i++) {
            tooMany.put("key-" + i, "value");
        }

        assertThatThrownBy(() -> intake("ORDER-1", "key-1", null, tooMany))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at most " + PaymentIntake.MAX_METADATA_ENTRIES);
    }

    @Test
    @DisplayName("an overlong metadata key or value is rejected")
    void rejectsOversizedMetadataEntry() {
        Map<String, String> longKey =
                Map.of("K".repeat(PaymentIntake.MAX_METADATA_KEY_LENGTH + 1), "value");
        Map<String, String> longValue =
                Map.of("orderId", "V".repeat(PaymentIntake.MAX_METADATA_VALUE_LENGTH + 1));

        assertThatThrownBy(() -> intake("ORDER-1", "key-1", null, longKey))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("metadata key");
        assertThatThrownBy(() -> intake("ORDER-1", "key-1", null, longValue))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("metadata value");
    }

    @Test
    @DisplayName("a null metadata value is rejected rather than stored as JSON null")
    void rejectsNullMetadataValue() {
        Map<String, String> withNull = new HashMap<>();
        withNull.put("orderId", null);

        assertThatThrownBy(() -> intake("ORDER-1", "key-1", null, withNull))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("metadata value");
    }

    @Test
    @DisplayName("metadata is copied, so a later change to the caller's map is not stored")
    void copiesMetadata() {
        Map<String, String> mutable = new HashMap<>();
        mutable.put("orderId", "ORDER-1");

        PaymentIntake intake = intake("ORDER-1", "key-1", null, mutable);
        mutable.put("added", "later");

        assertThat(intake.metadata()).containsExactly(Map.entry("orderId", "ORDER-1"));
    }

    @Test
    @DisplayName("a zero amount is rejected: the API validates this first, so reaching here is a bug")
    void rejectsNonPositiveAmount() {
        assertThatThrownBy(
                        () ->
                                new PaymentIntake(
                                        UUID.randomUUID(),
                                        UUID.randomUUID(),
                                        UUID.randomUUID(),
                                        "ORDER-1",
                                        "key-1",
                                        Money.zero("VND"),
                                        null,
                                        null,
                                        CREATED_AT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive");
    }

    @Test
    @DisplayName("every identifier and the timestamp are required")
    void rejectsMissingRequiredValues() {
        assertThatThrownBy(
                        () ->
                                new PaymentIntake(
                                        null,
                                        UUID.randomUUID(),
                                        UUID.randomUUID(),
                                        "ORDER-1",
                                        "key-1",
                                        Money.of("100", "VND"),
                                        null,
                                        null,
                                        CREATED_AT))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("paymentId");

        assertThatThrownBy(
                        () ->
                                new PaymentIntake(
                                        UUID.randomUUID(),
                                        UUID.randomUUID(),
                                        UUID.randomUUID(),
                                        "ORDER-1",
                                        "key-1",
                                        Money.of("100", "VND"),
                                        null,
                                        null,
                                        null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("createdAt");
    }
}
