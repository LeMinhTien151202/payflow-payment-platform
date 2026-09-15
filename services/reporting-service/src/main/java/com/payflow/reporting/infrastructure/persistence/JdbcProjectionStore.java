package com.payflow.reporting.infrastructure.persistence;

import com.payflow.reporting.application.*;
import com.payflow.reporting.application.port.ProjectionStore;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Component
class JdbcProjectionStore implements ProjectionStore{
 private final JdbcClient jdbc;private final ProjectionEventParser parser;
 JdbcProjectionStore(JdbcClient jdbc,ObjectMapper json){this.jdbc=jdbc;this.parser=new ProjectionEventParser(json);}
 @Override @Transactional
 public boolean appendAndProject(ProjectionEvent event){
  int inserted=jdbc.sql("""
   insert into reporting.event_log(event_id,event_type,event_version,aggregate_id,occurred_at,payload,received_at)
   values(:id,:type,:version,:aggregate,:occurred,cast(:payload as jsonb),clock_timestamp())
   on conflict(event_id) do nothing
   """).param("id",event.eventId()).param("type",event.eventType()).param("version",event.eventVersion())
   .param("aggregate",event.aggregateId()).param("occurred",event.occurredAt().atOffset(ZoneOffset.UTC))
   .param("payload",event.rawPayload()).update();
  if(inserted==0)return false;
  project(activeGeneration(),event);
  return true;
 }
 @Override public UUID activeGeneration(){
  return jdbc.sql("select generation_id from reporting.active_generation where singleton=true").query(UUID.class).single();
 }
 private void project(UUID generation,ProjectionEvent event){
  var d=event.data();
  switch(event.eventType()){
   case "payment.created" -> jdbc.sql("""
    insert into reporting.payment_projection(generation_id,payment_id,merchant_id,customer_id,amount,currency,status,
      refunded_amount,created_at,updated_at)
    values(:generation,:payment,:merchant,:customer,:amount,:currency,'CREATED',0,:created,:created)
    on conflict(generation_id,payment_id) do nothing
    """).param("generation",generation).param("payment",uuid(d,"paymentId")).param("merchant",uuid(d,"merchantId"))
     .param("customer",uuid(d,"customerId")).param("amount",decimal(d,"amount")).param("currency",d.required("currency").stringValue())
     .param("created",Instant.parse(d.required("createdAt").stringValue()).atOffset(ZoneOffset.UTC)).update();
   case "payment.succeeded" -> jdbc.sql("""
    insert into reporting.payment_projection(generation_id,payment_id,merchant_id,customer_id,amount,currency,status,
      refunded_amount,created_at,completed_at,updated_at)
    values(:generation,:payment,:merchant,:customer,:amount,:currency,'SUCCEEDED',0,:completed,:completed,:completed)
    on conflict(generation_id,payment_id) do update set status='SUCCEEDED',merchant_id=excluded.merchant_id,
      customer_id=excluded.customer_id,amount=excluded.amount,currency=excluded.currency,
      completed_at=excluded.completed_at,updated_at=excluded.updated_at
    """).param("generation",generation).param("payment",uuid(d,"paymentId")).param("merchant",uuid(d,"merchantId"))
     .param("customer",uuid(d,"customerId")).param("amount",decimal(d,"amount")).param("currency",d.required("currency").stringValue())
     .param("completed",Instant.parse(d.required("completedAt").stringValue()).atOffset(ZoneOffset.UTC)).update();
   case "payment.failed" -> jdbc.sql("""
    update reporting.payment_projection set status='FAILED',completed_at=:at,updated_at=:at
    where generation_id=:generation and payment_id=:payment
    """).param("generation",generation).param("payment",uuid(d,"paymentId"))
     .param("at",Instant.parse(d.required("failedAt").stringValue()).atOffset(ZoneOffset.UTC)).update();
   case "payment.cancelled" -> jdbc.sql("""
    update reporting.payment_projection set status='CANCELLED',completed_at=:at,updated_at=:at
    where generation_id=:generation and payment_id=:payment
    """).param("generation",generation).param("payment",uuid(d,"paymentId"))
     .param("at",Instant.parse(d.required("cancelledAt").stringValue()).atOffset(ZoneOffset.UTC)).update();
   case "refund.succeeded" -> jdbc.sql("""
    update reporting.payment_projection set refunded_amount=refunded_amount+:amount,
      status=case when refunded_amount+:amount>=amount then 'REFUNDED' else 'PARTIALLY_REFUNDED' end,
      updated_at=:at where generation_id=:generation and payment_id=:payment
    """).param("generation",generation).param("payment",uuid(d,"paymentId")).param("amount",decimal(d,"amount"))
     .param("at",Instant.parse(d.required("completedAt").stringValue()).atOffset(ZoneOffset.UTC)).update();
   default -> { }
  }
 }
 @Override @Transactional
 public UUID rebuild(String actor,String correlationId,Instant now){
  UUID old=activeGeneration(),next=UUID.randomUUID();
  jdbc.sql("insert into reporting.projection_generations(id,status,created_at) values(:id,'BUILDING',:now)")
   .param("id",next).param("now",now.atOffset(ZoneOffset.UTC)).update();
  var events=jdbc.sql("select aggregate_id,payload::text from reporting.event_log order by occurred_at,event_id")
   .query((rs,n)->parser.parse(rs.getString("aggregate_id"),rs.getString("payload"))).list();
  events.forEach(event->project(next,event));
  String oldFingerprint=fingerprint(old),newFingerprint=fingerprint(next);
  if(!Objects.equals(oldFingerprint,newFingerprint)){
   jdbc.sql("update reporting.projection_generations set status='REJECTED',completed_at=clock_timestamp() where id=:id")
    .param("id",next).update();
   throw new IllegalStateException("Reporting rebuild fingerprint differs from active projection");
  }
  jdbc.sql("update reporting.projection_generations set status='READY',completed_at=clock_timestamp() where id=:id").param("id",next).update();
  jdbc.sql("update reporting.active_generation set generation_id=:id,activated_at=clock_timestamp() where singleton=true").param("id",next).update();
  jdbc.sql("update reporting.projection_generations set status='ACTIVE' where id=:id").param("id",next).update();
  jdbc.sql("update reporting.projection_generations set status='RETIRED' where id=:id").param("id",old).update();
  jdbc.sql("""
    insert into reporting.audit_records(id,actor_id,action,resource_id,before_value,after_value,correlation_id,created_at)
    values(:id,:actor,'REPORTING_REBUILD',:resource,:before,:after,:correlation,:now)
    """)
   .param("id",UUID.randomUUID()).param("actor",actor).param("resource",next).param("before",old.toString())
   .param("after",next.toString()).param("correlation",correlationId).param("now",now.atOffset(ZoneOffset.UTC)).update();
  return next;
 }
 private String fingerprint(UUID generation){
  return jdbc.sql("""
    select md5(coalesce(string_agg(payment_id::text||':'||status||':'||amount::text||':'||
    refunded_amount::text,'|' order by payment_id),''))
    from reporting.payment_projection where generation_id=:id
    """)
   .param("id",generation).query(String.class).single();
 }
 @Override @Transactional(readOnly=true)
 public List<DailyMetric> daily(UUID merchant,Instant from,Instant to){
  return jdbc.sql("""
   select (created_at at time zone 'UTC')::date metric_date,currency,count(*) total,
    count(*) filter(where status in('SUCCEEDED','PARTIALLY_REFUNDED','REFUNDED')) succeeded,
    count(*) filter(where status='FAILED') failed,
    coalesce(sum(amount) filter(where status in('SUCCEEDED','PARTIALLY_REFUNDED','REFUNDED')),0) gross,
    coalesce(sum(refunded_amount),0) refunded
   from reporting.payment_projection p join reporting.active_generation a on a.generation_id=p.generation_id
   where merchant_id=:merchant and created_at>=:from and created_at<:to
   group by metric_date,currency order by metric_date
   """).param("merchant",merchant).param("from",from.atOffset(ZoneOffset.UTC)).param("to",to.atOffset(ZoneOffset.UTC))
   .query((rs,n)->new DailyMetric(rs.getObject("metric_date",java.time.LocalDate.class),rs.getString("currency"),
    rs.getLong("total"),rs.getLong("succeeded"),rs.getLong("failed"),rs.getBigDecimal("gross"),rs.getBigDecimal("refunded"))).list();
 }
 private static UUID uuid(tools.jackson.databind.JsonNode n,String field){return UUID.fromString(n.required(field).stringValue());}
 private static BigDecimal decimal(tools.jackson.databind.JsonNode n,String field){
  var value=n.required(field);
  if(value.isNumber())return value.decimalValue();
  if(value.isTextual())return new BigDecimal(value.stringValue());
  throw new IllegalArgumentException(field+" must be numeric");
 }
}
