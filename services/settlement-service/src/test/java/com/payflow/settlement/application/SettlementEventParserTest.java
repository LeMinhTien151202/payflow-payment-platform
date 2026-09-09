package com.payflow.settlement.application;
import static org.assertj.core.api.Assertions.*;
import com.payflow.events.*; import com.payflow.events.payment.*;
import java.math.BigDecimal; import java.time.Instant; import java.util.UUID;
import org.junit.jupiter.api.Test; import tools.jackson.databind.json.JsonMapper;
class SettlementEventParserTest {
 private final JsonMapper json=JsonMapper.builder().build(); private final SettlementEventParser parser=new SettlementEventParser(json);
 @Test void parsesPaymentSucceededV2WithImmutableFeeSnapshot(){UUID payment=UUID.randomUUID(),merchant=UUID.randomUUID();var data=new PaymentSucceededV2Data(payment,merchant,UUID.randomUUID(),new BigDecimal("100.0000"),"VND","fee-v1",new BigDecimal("0.020000"),new BigDecimal("2.0000"),"VND","HALF_EVEN",Instant.parse("2026-08-08T01:00:00Z"));var envelope=EventEnvelope.of(UUID.randomUUID(),PaymentEvents.PAYMENT_SUCCEEDED_V2,payment.toString(),"corr-1","payment-service",data.completedAt(),data);var fact=parser.parse(payment.toString(),json.writeValueAsString(envelope)).orElseThrow();assertThat(fact.factType()).isEqualTo(SettlementFactType.PAYMENT_SUCCEEDED);assertThat(fact.contribution().netAmount()).isEqualByComparingTo("98");}
 @Test void rejectsLegacyPaymentSucceededBecauseFeeSnapshotIsAbsent(){UUID payment=UUID.randomUUID();String payload="""
   {"eventId":"%s","eventType":"payment.succeeded","eventVersion":1,"aggregateId":"%s","correlationId":"corr-1","occurredAt":"2026-08-08T01:00:00Z","data":{}}
   """.formatted(UUID.randomUUID(),payment);assertThatThrownBy(()->parser.parse(payment.toString(),payload)).hasMessageContaining("requires payment.succeeded v2");}
 @Test void ignoresUnrelatedEvent(){UUID id=UUID.randomUUID();String payload="""
   {"eventId":"%s","eventType":"risk.assessment-completed","eventVersion":1,"aggregateId":"%s","correlationId":"corr-1","occurredAt":"2026-08-08T01:00:00Z","data":{}}
   """.formatted(UUID.randomUUID(),id);assertThat(parser.parse(id.toString(),payload)).isEmpty();}
 @Test void rejectsMismatchedKafkaKey(){UUID id=UUID.randomUUID();String payload="""
   {"eventId":"%s","eventType":"unknown","eventVersion":1,"aggregateId":"%s","correlationId":"corr-1","occurredAt":"2026-08-08T01:00:00Z","data":{}}
   """.formatted(UUID.randomUUID(),id);assertThatThrownBy(()->parser.parse(UUID.randomUUID().toString(),payload)).hasMessageContaining("Kafka key");}
}
