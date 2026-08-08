package com.payflow.notification.application.webhook;
import static org.assertj.core.api.Assertions.*;
import java.time.Duration;
import org.junit.jupiter.api.Test;
class WebhookRetryPolicyTest {
 private final WebhookRetryPolicy policy=new WebhookRetryPolicy();
 @Test void retries429And5xxButNotPermanent4xx(){
  assertThat(policy.classify(1,429,false).retry()).isTrue();
  assertThat(policy.classify(2,503,false).delay()).isEqualTo(Duration.ofMinutes(2));
  assertThat(policy.classify(1,400,false).terminal()).isTrue();
 }
 @Test void boundsRetryCount(){assertThat(policy.classify(5,null,true).terminal()).isTrue();}
}
