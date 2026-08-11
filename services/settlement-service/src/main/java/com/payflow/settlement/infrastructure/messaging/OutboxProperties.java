package com.payflow.settlement.infrastructure.messaging;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
@ConfigurationProperties("payflow.outbox")
record OutboxProperties(@DefaultValue("true") boolean enabled,@DefaultValue("500ms") Duration pollInterval,
 @DefaultValue("100") int batchSize,@DefaultValue("120s") Duration lease,@DefaultValue("10") int maxAttempts,
 @DefaultValue("300s") Duration maxBackoff,@DefaultValue("30s") Duration deliveryTimeout){
 OutboxProperties {
  requirePositive(pollInterval,"pollInterval"); requirePositive(lease,"lease");
  requirePositive(maxBackoff,"maxBackoff"); requirePositive(deliveryTimeout,"deliveryTimeout");
  if(batchSize<1||maxAttempts<1)throw new IllegalArgumentException("outbox counts must be positive");
  if(lease.compareTo(deliveryTimeout)<=0)throw new IllegalArgumentException("outbox lease must exceed delivery timeout");
 }
 private static void requirePositive(Duration value,String name){if(value==null||value.isZero()||value.isNegative())throw new IllegalArgumentException("payflow.outbox."+name+" must be positive");}
}
