package com.payflow.notification.application.port;

import com.payflow.notification.application.delivery.NotificationClaimBatch;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Delivery-side access to the notification table.
 *
 * <p>No caller passes in a "now". Every timestamp that decides whether a row is due, or whether a
 * lease has expired, is produced by the database clock inside the statement that reads it. Workers
 * run on separate hosts and the row is the only thing they share, so one worker's clock is not an
 * authority the others can trust: a worker running fast would steal leases that have not expired
 * and send the same email twice, and a notification written against one clock but claimed against
 * another sits undelivered for the length of the drift.
 *
 * <p>{@code sentAt} is the exception. It is an audit fact reported by the email provider and is
 * never compared against anything, so it stays a value the caller supplies.
 */
public interface NotificationDeliveryStore {
    NotificationClaimBatch claim(String owner, Duration lease, int maxAttempts, int batchSize);

    boolean markSent(UUID notificationId, String owner, Instant sentAt);

    boolean markFailed(UUID notificationId, String owner, String failureCode);

    double oldestPendingAgeSeconds();
}
