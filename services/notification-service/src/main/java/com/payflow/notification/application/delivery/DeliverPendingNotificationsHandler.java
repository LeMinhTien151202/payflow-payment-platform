package com.payflow.notification.application.delivery;

import com.payflow.notification.application.port.EmailDeliveryPort;
import com.payflow.notification.application.port.NotificationDeliveryStore;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(
        prefix = "payflow.notification-delivery", name = "enabled", havingValue = "true",
        matchIfMissing = true)
public class DeliverPendingNotificationsHandler {

    private static final Pattern SAFE_FAILURE_CODE = Pattern.compile("[A-Z0-9_]{1,100}");

    private final NotificationDeliveryStore store;
    private final EmailDeliveryPort email;
    private final NotificationDeliveryPolicy policy;
    private final MeterRegistry metrics;
    private final Clock clock;
    private final String owner = "notification-worker-" + UUID.randomUUID();

    public DeliverPendingNotificationsHandler(
            NotificationDeliveryStore store,
            EmailDeliveryPort email,
            NotificationDeliveryPolicy policy,
            MeterRegistry metrics,
            Clock clock) {
        this.store = store;
        this.email = email;
        this.policy = policy;
        this.metrics = metrics;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${payflow.notification-delivery.poll-interval:500ms}")
    public void deliverDue() {
        var now = clock.instant();
        var batch = store.claim(owner, now, policy.lease(), policy.maxAttempts(), policy.batchSize());
        metrics.counter("payflow.notification.delivery", "outcome", "lease_exhausted")
                .increment(batch.exhaustedCount());
        metrics.summary("payflow.notification.pending.oldest.seconds")
                .record(store.oldestPendingAgeSeconds(now));
        for (var notification : batch.notifications()) {
            if (notification.reclaimed()) {
                metrics.counter("payflow.notification.delivery", "outcome", "reclaimed").increment();
            }
            deliver(notification);
        }
    }

    private void deliver(ClaimedNotification notification) {
        EmailDeliveryResult result;
        try {
            result = Objects.requireNonNull(
                    email.deliver(new EmailMessage(notification.id(), notification.recipientId(),
                            notification.templateCode(), notification.payload())),
                    "email provider returned no result");
        } catch (RuntimeException providerFailure) {
            record(notification.id(), store.markFailed(
                    notification.id(), owner, "EMAIL_PROVIDER_ERROR"), "failed");
            return;
        }
        if (result.completedAt().isBefore(notification.createdAt())) {
            record(notification.id(), store.markFailed(
                    notification.id(), owner, "EMAIL_PROVIDER_INVALID_TIME"), "failed");
        } else if (result.delivered()) {
            record(notification.id(), store.markSent(notification.id(), owner, result.completedAt()),
                    "sent");
        } else {
            record(notification.id(), store.markFailed(notification.id(), owner,
                    safeFailureCode(result.failureCode())), "failed");
        }
    }

    private void record(UUID notificationId, boolean persisted, String outcome) {
        if (persisted) {
            metrics.counter("payflow.notification.delivery", "outcome", outcome).increment();
        } else {
            metrics.counter("payflow.notification.delivery", "outcome", "lost_lease").increment();
        }
    }

    private static String safeFailureCode(String failureCode) {
        return failureCode != null && SAFE_FAILURE_CODE.matcher(failureCode).matches()
                ? failureCode
                : "EMAIL_PROVIDER_REJECTED";
    }
}
