package com.payflow.notification.infrastructure.delivery;

import com.payflow.notification.application.webhook.DeliverPendingWebhooksHandler;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name="payflow.webhook.enabled",havingValue="true")
class WebhookDeliveryJob {
 private final DeliverPendingWebhooksHandler handler;private final WebhookProperties props;
 private final String owner="notification-service:"+UUID.randomUUID();
 WebhookDeliveryJob(DeliverPendingWebhooksHandler handler,WebhookProperties props){this.handler=handler;this.props=props;}
 @Scheduled(fixedDelayString="${payflow.webhook.poll-interval:1s}")
 void run(){handler.deliver(owner,props.lease(),props.batchSize());}
}
