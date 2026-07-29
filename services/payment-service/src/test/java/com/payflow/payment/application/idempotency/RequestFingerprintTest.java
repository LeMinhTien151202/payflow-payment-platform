package com.payflow.payment.application.idempotency;

import static com.payflow.payment.application.CreatePaymentCommands.request;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.math.BigDecimal;
import com.payflow.payment.application.command.CreateRefundCommand;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The fingerprint decides whether a reused idempotency key is a retry or a conflict, so every test here is
 * about one of two failures: calling a retry a conflict (the client is rejected for behaving correctly), or
 * calling a conflict a retry (the client gets back a payment that is not the one it asked for).
 */
class RequestFingerprintTest {

    private static final UUID REFUND_PAYMENT_ID =
            UUID.fromString("c73e17b5-aaca-48da-9ed5-bb0937499f01");

    @Test
    @DisplayName("the same request always produces the same 64-character lowercase hex fingerprint")
    void isDeterministic() {
        String first = RequestFingerprint.of(request().build());
        String second = RequestFingerprint.of(request().build());

        assertThat(first).isEqualTo(second).hasSize(64).matches("[0-9a-f]{64}");
    }

    /**
     * The whole mechanism rests on this. The key is what the fingerprint is stored under; if it were also an
     * input, a retry carrying the same key would hash differently and every retry would be a 409.
     */
    @Test
    @DisplayName("the idempotency key is not part of the fingerprint")
    void ignoresIdempotencyKey() {
        assertThat(RequestFingerprint.of(request().key("key-one").build()))
                .isEqualTo(RequestFingerprint.of(request().key("key-two").build()));
    }

    @Test
    @DisplayName("metadata order does not change the fingerprint")
    void ignoresMetadataOrder() {
        Map<String, String> oneOrder = new LinkedHashMap<>();
        oneOrder.put("orderId", "ORDER-1");
        oneOrder.put("channel", "WEB");
        oneOrder.put("campaign", "TET-2026");

        Map<String, String> otherOrder = new LinkedHashMap<>();
        otherOrder.put("campaign", "TET-2026");
        otherOrder.put("orderId", "ORDER-1");
        otherOrder.put("channel", "WEB");

        assertThat(RequestFingerprint.of(request().metadata(oneOrder).build()))
                .isEqualTo(RequestFingerprint.of(request().metadata(otherOrder).build()));
    }

    /**
     * A client that formats its amounts differently between the original request and the retry — a JSON
     * library writing {@code 5E+5}, say — has still sent one payment.
     */
    @Test
    @DisplayName("500000, 500000.00 and 5E+5 are one amount")
    void normalisesAmountScale() {
        String plain = RequestFingerprint.of(request().amount("500000").build());

        assertThat(RequestFingerprint.of(request().amount("500000.00").build())).isEqualTo(plain);
        assertThat(RequestFingerprint.of(request().amount("5E+5").build())).isEqualTo(plain);
    }

    @Test
    @DisplayName("a different amount is a different request")
    void separatesAmounts() {
        assertThat(RequestFingerprint.of(request().amount("500000").build()))
                .isNotEqualTo(RequestFingerprint.of(request().amount("500000.01").build()));
    }

    /**
     * The case the length prefix exists for. Concatenating values would give both of these the same bytes,
     * and a client that reused a key across them would be handed the wrong payment.
     */
    @Test
    @DisplayName("a value boundary cannot be shifted between two fields")
    void resistsFieldBoundaryCollision() {
        String left = RequestFingerprint.of(request().reference("AB").description("C").build());
        String right = RequestFingerprint.of(request().reference("A").description("BC").build());

        assertThat(left).isNotEqualTo(right);
    }

    @Test
    @DisplayName("an absent description is not the same as an empty one")
    void separatesNullFromEmpty() {
        assertThat(RequestFingerprint.of(request().description(null).build()))
                .isNotEqualTo(RequestFingerprint.of(request().description("").build()));
    }

    /** A description that reads {@code "null"} must not collide with the marker used for absent values. */
    @Test
    @DisplayName("the literal text null is not the same as an absent value")
    void separatesNullFromTheWordNull() {
        assertThat(RequestFingerprint.of(request().description(null).build()))
                .isNotEqualTo(RequestFingerprint.of(request().description("null").build()));
    }

    @Test
    @DisplayName("added metadata is a different request")
    void separatesMetadata() {
        String withoutChannel = RequestFingerprint.of(request().metadata(Map.of("orderId", "ORDER-1")).build());
        String withChannel =
                RequestFingerprint.of(
                        request().metadata(Map.of("orderId", "ORDER-1", "channel", "WEB")).build());

        assertThat(withoutChannel).isNotEqualTo(withChannel);
    }

    @Test
    @DisplayName("no metadata is not the same as one empty-valued entry")
    void separatesAbsentMetadataFromAnEmptyValue() {
        assertThat(RequestFingerprint.of(request().metadata(Map.of()).build()))
                .isNotEqualTo(RequestFingerprint.of(request().metadata(Map.of("orderId", "")).build()));
    }

    @Test
    @DisplayName("currency is part of the request")
    void separatesCurrencies() {
        assertThat(RequestFingerprint.of(request().currency("VND").build()))
                .isNotEqualTo(RequestFingerprint.of(request().currency("USD").build()));
    }

    /**
     * The scope already contains the merchant id, so two merchants can never read each other's records. This
     * is the second lock on the same door: even given one scope, two merchants do not share a fingerprint.
     */
    @Test
    @DisplayName("the merchant is part of the request")
    void separatesMerchants() {
        UUID other = UUID.fromString("33333333-3333-4333-8333-333333333333");

        assertThat(RequestFingerprint.of(request().build()))
                .isNotEqualTo(RequestFingerprint.of(request().merchant(other).build()));
    }

    @Test
    @DisplayName("the customer and the funding account are part of the request")
    void separatesPartiesAndAccounts() {
        UUID otherCustomer = UUID.fromString("7c9e6679-7425-40de-944b-e07fc1f90ae7");
        UUID otherAccount = UUID.fromString("16fd2706-8baf-433b-82eb-8c7fada847da");
        String base = RequestFingerprint.of(request().build());

        assertThat(RequestFingerprint.of(request().customer(otherCustomer).build())).isNotEqualTo(base);
        assertThat(RequestFingerprint.of(request().sourceAccount(otherAccount).build())).isNotEqualTo(base);
    }

    @Test
    @DisplayName("refund fingerprint normalises amount and ignores key and retrying actor")
    void normalisesRefundRetries() {
        CreateRefundCommand first = refund("key-1", "actor-1", "200000.00", "returned");
        CreateRefundCommand retry = refund("key-2", "actor-2", "2E+5", "returned");

        assertThat(RequestFingerprint.of(first)).isEqualTo(RequestFingerprint.of(retry));
    }

    @Test
    @DisplayName("refund fingerprint includes payment, amount and reason")
    void separatesDifferentRefundIntent() {
        String base = RequestFingerprint.of(refund("key", "actor", "200", "returned"));

        assertThat(RequestFingerprint.of(refund("key", "actor", "201", "returned")))
                .isNotEqualTo(base);
        assertThat(RequestFingerprint.of(refund("key", "actor", "200", "duplicate")))
                .isNotEqualTo(base);
        assertThat(RequestFingerprint.of(new CreateRefundCommand(
                        com.payflow.payment.PaymentTokens.MERCHANT_ID,
                        "actor",
                        UUID.randomUUID(),
                        "key",
                        new BigDecimal("200"),
                        "returned")))
                .isNotEqualTo(base);
    }

    private static CreateRefundCommand refund(
            String key, String actor, String amount, String reason) {
        return new CreateRefundCommand(
                com.payflow.payment.PaymentTokens.MERCHANT_ID,
                actor,
                REFUND_PAYMENT_ID,
                key,
                new BigDecimal(amount),
                reason);
    }
}
