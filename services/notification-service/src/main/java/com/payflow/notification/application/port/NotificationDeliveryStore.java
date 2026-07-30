package com.payflow.notification.application.port;

import com.payflow.notification.application.delivery.NotificationClaimBatch;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

public interface NotificationDeliveryStore {
    NotificationClaimBatch claim(
            String owner, Instant now, Duration lease, int maxAttempts, int batchSize);

    boolean markSent(UUID notificationId, String owner, Instant sentAt);

    boolean markFailed(UUID notificationId, String owner, String failureCode);

    double oldestPendingAgeSeconds(Instant now);
}
