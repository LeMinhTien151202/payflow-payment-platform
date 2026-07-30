package com.payflow.notification.application.port;

import com.payflow.notification.application.notification.OutcomeNotificationIntent;
import java.util.Objects;
import java.util.UUID;

public record NotificationRecord(UUID id, OutcomeNotificationIntent intent) {
    public NotificationRecord {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(intent, "intent");
    }
}
