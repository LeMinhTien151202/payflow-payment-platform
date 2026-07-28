package com.payflow.notification.application.delivery;

import java.time.Instant;
import java.util.Objects;

public record EmailDeliveryResult(boolean delivered, Instant completedAt, String failureCode) {

    public EmailDeliveryResult {
        Objects.requireNonNull(completedAt, "completedAt");
        if (delivered && failureCode != null) {
            throw new IllegalArgumentException("successful delivery cannot have a failure code");
        }
        if (!delivered && (failureCode == null || failureCode.isBlank())) {
            throw new IllegalArgumentException("failed delivery requires a stable failure code");
        }
    }

    public static EmailDeliveryResult delivered(Instant completedAt) {
        return new EmailDeliveryResult(true, completedAt, null);
    }

    public static EmailDeliveryResult failed(Instant completedAt, String stableFailureCode) {
        return new EmailDeliveryResult(false, completedAt, stableFailureCode);
    }
}
