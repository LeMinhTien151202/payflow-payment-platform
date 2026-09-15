package com.payflow.notification.application.webhook;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

/** Safe operations view: deliberately excludes the signed raw body and webhook secret. */
@Schema(description = "One terminal webhook delivery that can be manually retried")
public record WebhookDeadItem(
        UUID deliveryId,
        UUID merchantId,
        UUID eventId,
        String eventType,
        int attemptCount,
        Integer responseStatus,
        String responseBodyExcerpt,
        String failureCode,
        Instant nextAttemptAt,
        Instant createdAt) {}
