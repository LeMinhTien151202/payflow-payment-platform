package com.payflow.notification.application.port;

import com.payflow.notification.application.notification.OutcomeNotificationIntent;
import com.payflow.notification.application.webhook.WebhookDeadPage;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface WebhookDeliveryStore {
    boolean saveIfEligible(OutcomeNotificationIntent intent);
    List<ClaimedWebhook> claim(String owner,Duration lease,int limit);
    boolean delivered(UUID id,String owner,int responseStatus,Instant deliveredAt);
    boolean retry(UUID id,String owner,int responseStatus,String safeExcerpt,Instant nextAttemptAt);
    boolean dead(UUID id,String owner,Integer responseStatus,String safeExcerpt,String failureCode);
    boolean manualRequeue(UUID id,String actor,String correlationId,Instant now);
    WebhookDeadPage findDead(int page, int size);
    record ClaimedWebhook(UUID id,UUID merchantId,UUID eventId,String eventType,String rawBody,int attemptCount) {}
}
