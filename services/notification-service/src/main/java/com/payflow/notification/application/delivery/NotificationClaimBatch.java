package com.payflow.notification.application.delivery;

import java.util.List;

public record NotificationClaimBatch(List<ClaimedNotification> notifications, int exhaustedCount) {
    public NotificationClaimBatch {
        notifications = List.copyOf(notifications);
        if (exhaustedCount < 0) {
            throw new IllegalArgumentException("exhaustedCount must not be negative");
        }
    }
}
