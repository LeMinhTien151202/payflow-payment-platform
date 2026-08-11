package com.payflow.settlement;
import static org.assertj.core.api.Assertions.*;
import com.payflow.events.*; import com.payflow.events.payment.*;
import com.payflow.settlement.application.*;
import java.math.BigDecimal; import java.time.*; import java.util.UUID;
import org.junit.jupiter.api.Test; import org.springframework.beans.factory.annotation.Autowired; import org.springframework.boot.test.context.SpringBootTest; import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.NONE)
class SettlementPersistenceIT extends AbstractPostgresIT {
 @Autowired HandleSettlementEventHandler handler; @Autowired SettlementOperationsService operations; @Autowired JdbcTemplate jdbc; @Autowired ObjectMapper json;
 @Test void calculatesCompletesAndReconcilesOnePaymentIdempotently(){
  UUID payment=UUID.randomUUID(),merchant=UUID.randomUUID();Instant at=Instant.parse("2026-08-08T01:00:00Z");
  var data=new PaymentSucceededV2Data(payment,merchant,UUID.randomUUID(),new BigDecimal("100000.0000"),"VND","fee-v1",new BigDecimal("0.020000"),new BigDecimal("2000.0000"),"VND","HALF_EVEN",at);
  var envelope=EventEnvelope.of(UUID.randomUUID(),PaymentEvents.PAYMENT_SUCCEEDED_V2,payment.toString(),"settlement-it","payment-service",at,data);
  String payload=json.writeValueAsString(envelope);
  assertThat(handler.handle(payment.toString(),payload)).isEqualTo(SettlementProcessingResult.APPLIED);
  assertThat(handler.handle(payment.toString(),payload)).isEqualTo(SettlementProcessingResult.DUPLICATE_EVENT);
  fact(payment,"ledger.payment-posted","{\"paymentId\":\""+payment+"\",\"amount\":\"100000.0000\",\"currency\":\"VND\"}",at);
  fact(payment,"account.funds-captured","{\"paymentId\":\""+payment+"\",\"amount\":\"100000.0000\",\"currency\":\"VND\"}",at);
  var batch=operations.calculate(LocalDate.of(2026,8,8),"operator-it","settlement-it").getFirst();
  assertThat(batch.status().name()).isEqualTo("READY"); assertThat(batch.netAmount()).isEqualByComparingTo("98000");
  assertThat(operations.complete(batch.id(),"operator-it","settlement-it").status().name()).isEqualTo("COMPLETED");
  assertThat(operations.reconcile(LocalDate.of(2026,8,8),"operator-it","settlement-it").openIssueCount()).isZero();
  assertThat(jdbc.queryForObject("select count(*) from settlement_runtime.outbox_events where event_type='settlement.completed'",Integer.class)).isOne();
 }
 private void fact(UUID payment,String type,String data,Instant at){String payload="{\"eventId\":\""+UUID.randomUUID()+"\",\"eventType\":\""+type+"\",\"eventVersion\":1,\"aggregateId\":\""+payment+"\",\"correlationId\":\"settlement-it\",\"occurredAt\":\""+at+"\",\"data\":"+data+"}";assertThat(handler.handle(payment.toString(),payload)).isEqualTo(SettlementProcessingResult.APPLIED);}
}
