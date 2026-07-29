package com.payflow.payment.application.idempotency;

import com.payflow.payment.application.command.CreatePaymentCommand;
import com.payflow.payment.application.command.CreateRefundCommand;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Turns payment/refund write requests into fingerprints stored in
 * {@code idempotency_records.request_hash}.
 *
 * <p>The fingerprint answers one question: is this the same request as the one that used this key before?
 * Comparing raw request bodies would answer it wrongly, because a client that reorders JSON members or
 * writes {@code 500000.00} instead of {@code 500000} has sent the same payment. Everything here exists to
 * make equivalent requests produce equal strings, and different requests produce different ones.
 *
 * <p>Three properties matter, and each is a decision rather than a detail:
 *
 * <ul>
 *   <li><strong>Length-prefixed.</strong> Every value is encoded as {@code name:length:value|}. Plain
 *       concatenation would give reference {@code "AB"} with description {@code "C"} the same bytes as
 *       reference {@code "A"} with description {@code "BC"}, and those are different payments.
 *   <li><strong>Metadata sorted by key.</strong> JSON member order is not significant, so two orderings of
 *       the same metadata must not look like two different requests.
 *   <li><strong>Amount stripped of trailing zeros.</strong> {@code 500000}, {@code 500000.00} and
 *       {@code 5E+5} are one amount and must fingerprint alike.
 * </ul>
 *
 * <p>The idempotency key itself is excluded — it is the lookup key, not part of what is being compared.
 * The correlation id is excluded too: it differs on every retry, which would make every retry a conflict.
 *
 * <p>SHA-256 is used for a stable fixed width, not for secrecy. Nothing here is a credential and the hash
 * is never returned to a caller, so the comparison in {@link IdempotentResponse#matches(String)} does not
 * need to be constant-time. 64 hex characters fit {@code VARCHAR(128)} with room for a longer algorithm.
 */
public final class RequestFingerprint {

    private static final String ALGORITHM = "SHA-256";

    private RequestFingerprint() {
    }

    /** Lowercase hex SHA-256 of the canonical encoding of {@code command}. */
    public static String of(CreatePaymentCommand command) {
        StringBuilder canonical = new StringBuilder();

        field(canonical, "merchantId", text(command.merchantId()));
        field(canonical, "merchantReference", command.merchantReference());
        field(canonical, "customerId", text(command.customerId()));
        field(canonical, "sourceAccountId", text(command.sourceAccountId()));
        field(canonical, "amount", amount(command.amount()));
        field(canonical, "currency", command.currency());
        field(canonical, "description", command.description());

        // TreeMap rather than a sorted stream: the keys are strings and natural ordering is the one
        // property this needs to keep across JVM versions.
        for (Map.Entry<String, String> entry : new TreeMap<>(command.metadata()).entrySet()) {
            field(canonical, "metadata." + entry.getKey(), entry.getValue());
        }

        return hex(canonical.toString());
    }

    /** Canonical refund payload. Actor is excluded so a legitimate retry can be replayed by the merchant. */
    public static String of(CreateRefundCommand command) {
        StringBuilder canonical = new StringBuilder();
        field(canonical, "merchantId", text(command.merchantId()));
        field(canonical, "paymentId", text(command.paymentId()));
        field(canonical, "amount", amount(command.amount()));
        field(canonical, "reason", command.reason());
        return hex(canonical.toString());
    }

    private static String text(UUID value) {
        return value == null ? null : value.toString();
    }

    /**
     * Normalises without validating. An amount with more precision than the column accepts is rejected by
     * {@code Money}, with a message about scale; throwing here instead would report a fingerprinting
     * failure for what is a plain validation error.
     */
    private static String amount(BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros().toPlainString();
    }

    private static void field(StringBuilder target, String name, String value) {
        target.append(name).append(':');
        if (value == null) {
            // A distinct marker rather than an empty string, so an absent description and an empty one
            // are not the same request.
            target.append("null");
        } else {
            target.append(value.length()).append(':').append(value);
        }
        target.append('|');
    }

    private static String hex(String canonical) {
        try {
            MessageDigest digest = MessageDigest.getInstance(ALGORITHM);
            return HexFormat.of().formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            // Every JVM is required to provide SHA-256. Wrapped rather than declared, because no caller
            // has a meaningful response to an absent standard digest.
            throw new IllegalStateException(ALGORITHM + " is not available", impossible);
        }
    }
}
