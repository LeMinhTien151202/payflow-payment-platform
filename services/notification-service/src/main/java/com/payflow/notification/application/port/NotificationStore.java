package com.payflow.notification.application.port;

import java.util.Optional;
import java.util.UUID;

public interface NotificationStore {
    Optional<NotificationRecord> findByBusinessReference(String referenceType, UUID referenceId);

    boolean saveIfAbsent(NotificationRecord record);
}
