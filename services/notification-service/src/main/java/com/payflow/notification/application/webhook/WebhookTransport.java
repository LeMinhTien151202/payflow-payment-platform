package com.payflow.notification.application.webhook;

import java.util.UUID;

public interface WebhookTransport {
    Result send(UUID merchantId,UUID eventId,String eventType,String rawBody);
    record Result(Integer status,String safeExcerpt,boolean networkFailure){}
}
