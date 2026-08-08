package com.payflow.notification.application.webhook;
import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;
class WebhookSignatureTest {
 @Test void signsExactTimestampAndRawBody(){
  String body="{\"amount\":\"10.0000\"}";
  String signature=WebhookSignature.sign("secret",1700000000L,body);
  assertThat(WebhookSignature.verify("secret",1700000000L,body,signature)).isTrue();
  assertThat(WebhookSignature.verify("secret",1700000001L,body,signature)).isFalse();
  assertThat(WebhookSignature.verify("secret",1700000000L,"{\"amount\":\"10.00\"}",signature)).isFalse();
 }
}
