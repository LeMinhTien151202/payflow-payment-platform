package com.payflow.notification.infrastructure.delivery;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("payflow.webhook")
public record WebhookProperties(boolean enabled,Duration pollInterval,int batchSize,Duration lease,
 URI endpointUri,String signingSecret,Duration httpTimeout,
 boolean allowUnsafeLocalTargets){
 public WebhookProperties{
  if(batchSize<1||batchSize>100)throw new IllegalArgumentException("Webhook batch size must be 1..100");
  if(lease.isZero()||lease.isNegative()||httpTimeout.isZero()||httpTimeout.isNegative())throw new IllegalArgumentException("Webhook durations must be positive");
  if(enabled&&(endpointUri==null||signingSecret==null||signingSecret.isBlank()))
   throw new IllegalArgumentException("Enabled webhook delivery requires endpoint URI and signing secret");
 }
}
