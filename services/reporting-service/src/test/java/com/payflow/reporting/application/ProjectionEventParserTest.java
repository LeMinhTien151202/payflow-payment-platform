package com.payflow.reporting.application;
import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
class ProjectionEventParserTest{
 private final ProjectionEventParser parser=new ProjectionEventParser(JsonMapper.builder().build());
 private final String payload="""
  {"eventId":"11111111-1111-4111-8111-111111111111","eventType":"payment.created",
  "eventVersion":1,"aggregateId":"22222222-2222-4222-8222-222222222222","occurredAt":"2026-08-01T00:00:00Z",
  "data":{"paymentId":"22222222-2222-4222-8222-222222222222"}}
  """;
 @Test void parsesSupportedEnvelope(){assertThat(parser.parse("22222222-2222-4222-8222-222222222222",payload).eventType()).isEqualTo("payment.created");}
 @Test void rejectsWrongKey(){assertThatThrownBy(()->parser.parse("wrong",payload)).isInstanceOf(IllegalArgumentException.class);}
 @Test void acceptsPaymentSucceededV2(){String v2=payload.replace("payment.created","payment.succeeded").replace("\"eventVersion\":1","\"eventVersion\":2");assertThat(parser.parse("22222222-2222-4222-8222-222222222222",v2).eventVersion()).isEqualTo(2);}
 @Test void rejectsV2ForOtherEventTypes(){String v2=payload.replace("\"eventVersion\":1","\"eventVersion\":2");assertThatThrownBy(()->parser.parse("22222222-2222-4222-8222-222222222222",v2)).hasMessageContaining("Unsupported");}
}
