package com.payflow.notification.application.delivery;

import com.payflow.notification.application.port.EmailDeliveryPort;
import com.payflow.notification.domain.model.Notification;
import com.payflow.notification.domain.model.NotificationStatus;
import java.util.Objects;

/**
 * Executes the Phase 1B email-mock boundary.
 *
 * <p>A production worker will load/claim a persisted notification, call the delivery adapter outside
 * the database transaction, and then persist the outcome conditionally. This Docker-free handler
 * deliberately does not claim to provide that transaction or multi-instance concurrency protocol.
 */
public final class DeliverEmailNotificationHandler {

    private final EmailDeliveryPort deliveryPort;

    public DeliverEmailNotificationHandler(EmailDeliveryPort deliveryPort) {
        this.deliveryPort = Objects.requireNonNull(deliveryPort, "deliveryPort");
    }

    public DeliveryDisposition handle(Notification notification) {
        Objects.requireNonNull(notification, "notification");
        if (notification.status() != NotificationStatus.PENDING) {
            return DeliveryDisposition.ALREADY_FINALIZED;
        }

        EmailMessage message = new EmailMessage(
                notification.id(),
                notification.recipientId(),
                notification.templateCode(),
                notification.payload());
        EmailDeliveryResult result = Objects.requireNonNull(
                deliveryPort.deliver(message), "deliveryPort result");

        if (result.delivered()) {
            notification.recordSent(result.completedAt());
            return DeliveryDisposition.SENT;
        }
        notification.recordFailure(result.completedAt(), result.failureCode());
        return DeliveryDisposition.FAILED;
    }
}
