package com.payflow.notification.application.notification;

import com.payflow.notification.application.inbox.EventProcessingResult;
import com.payflow.notification.application.inbox.IncomingEventIdentity;
import com.payflow.notification.application.port.NotificationRecord;
import com.payflow.notification.application.port.NotificationStore;
import com.payflow.notification.application.port.ProcessedEventStore;
import com.payflow.notification.application.port.WebhookDeliveryStore;
import com.payflow.notification.domain.exception.NotificationInvariantViolationException;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class CreateOutcomeNotificationHandler {

    public static final String CONSUMER_NAME = "notification-outcome-v1";

    private final ProcessedEventStore inbox;
    private final NotificationStore notifications;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final WebhookDeliveryStore webhooks;

    public CreateOutcomeNotificationHandler(
            ProcessedEventStore inbox,
            NotificationStore notifications,
            WebhookDeliveryStore webhooks,
            TransactionTemplate transactions,
            Clock clock) {
        this.inbox = inbox;
        this.notifications = notifications;
        this.webhooks = webhooks;
        this.transactions = transactions;
        this.clock = clock;
    }

    public EventProcessingResult handle(OutcomeNotificationIntent intent) {
        Objects.requireNonNull(intent, "intent");
        EventProcessingResult result = transactions.execute(status -> persist(intent));
        return Objects.requireNonNull(result, "transaction returned no result");
    }

    private EventProcessingResult persist(OutcomeNotificationIntent intent) {
        boolean firstDelivery = inbox.recordIfNew(new IncomingEventIdentity(
                intent.sourceEventId(), CONSUMER_NAME, intent.sourceEventType(),
                intent.aggregateId(), clock.instant()));
        if (!firstDelivery) {
            return EventProcessingResult.DUPLICATE;
        }

        var existing = notifications.findByBusinessReference(
                intent.businessReferenceType(), intent.businessReferenceId());
        if (existing.isPresent()) {
            requireSameIntent(existing.orElseThrow().intent(), intent);
            return EventProcessingResult.BUSINESS_DUPLICATE;
        }

        if (notifications.saveIfAbsent(new NotificationRecord(UUID.randomUUID(), intent))) {
            webhooks.saveIfEligible(intent);
            return EventProcessingResult.PROCESSED;
        }

        var concurrent = notifications
                .findByBusinessReference(intent.businessReferenceType(), intent.businessReferenceId())
                .orElseThrow(() -> new IllegalStateException(
                        "notification conflict disappeared inside transaction"));
        requireSameIntent(concurrent.intent(), intent);
        return EventProcessingResult.BUSINESS_DUPLICATE;
    }

    private static void requireSameIntent(
            OutcomeNotificationIntent stored, OutcomeNotificationIntent incoming) {
        boolean same = stored.sourceEventType().equals(incoming.sourceEventType())
                && stored.aggregateId().equals(incoming.aggregateId())
                && stored.businessReferenceType().equals(incoming.businessReferenceType())
                && stored.businessReferenceId().equals(incoming.businessReferenceId())
                && stored.recipientType().equals(incoming.recipientType())
                && stored.recipientId().equals(incoming.recipientId())
                && stored.templateCode().equals(incoming.templateCode())
                && stored.payload().equals(incoming.payload());
        if (!same) {
            throw new NotificationInvariantViolationException(
                    "business reference already has a notification for a different outcome");
        }
    }
}
