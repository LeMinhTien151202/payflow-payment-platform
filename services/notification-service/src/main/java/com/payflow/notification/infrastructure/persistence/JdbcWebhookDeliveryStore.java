package com.payflow.notification.infrastructure.persistence;

import com.payflow.notification.application.notification.OutcomeNotificationIntent;
import com.payflow.notification.application.port.WebhookDeliveryStore;
import com.payflow.notification.application.webhook.WebhookDeadItem;
import com.payflow.notification.application.webhook.WebhookDeadPage;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.*;
import tools.jackson.databind.ObjectMapper;

@Component
class JdbcWebhookDeliveryStore implements WebhookDeliveryStore {
 private final JdbcClient jdbc; private final ObjectMapper json;
 JdbcWebhookDeliveryStore(JdbcClient jdbc,ObjectMapper json){this.jdbc=jdbc;this.json=json;}
 @Override @Transactional(propagation=Propagation.MANDATORY)
 public boolean saveIfEligible(OutcomeNotificationIntent intent){
  String merchantText=intent.payload().get("merchantId");
  if(merchantText==null&&"MERCHANT".equals(intent.recipientType()))merchantText=intent.recipientId();
  if(merchantText==null)return false;
  UUID merchantId=UUID.fromString(merchantText);
  Map<String,Object> body=new LinkedHashMap<>();
  body.put("eventId",intent.sourceEventId());body.put("eventType",intent.sourceEventType());
  body.put("occurredAt",intent.createdAt());body.put("data",intent.payload());
  return jdbc.sql("""
    insert into notification.webhook_deliveries(id,merchant_id,event_id,event_type,raw_body,status,
      attempt_count,next_attempt_at,created_at)
    values(:id,:merchant,:event,:type,cast(:body as jsonb),'PENDING',0,:now,:now)
    on conflict(merchant_id,event_id) do nothing
    """).param("id",UUID.randomUUID()).param("merchant",merchantId).param("event",intent.sourceEventId())
    .param("type",intent.sourceEventType()).param("body",json.writeValueAsString(body))
    .param("now",intent.createdAt().atOffset(ZoneOffset.UTC)).update()==1;
 }
 @Override @Transactional
 public List<ClaimedWebhook> claim(String owner,Duration lease,int limit){
  return jdbc.sql("""
    update notification.webhook_deliveries set status='PROCESSING',lock_owner=:owner,
      lock_until=clock_timestamp()+make_interval(secs=>:lease),attempt_count=attempt_count+1
    where id in(select id from notification.webhook_deliveries
      where (status='PENDING' and next_attempt_at<=clock_timestamp())
         or (status='PROCESSING' and lock_until<clock_timestamp())
      order by created_at limit :limit for update skip locked)
    returning id,merchant_id,event_id,event_type,raw_body::text,attempt_count
    """).param("owner",owner).param("lease",lease.toSeconds()).param("limit",limit)
    .query((rs,n)->new ClaimedWebhook(rs.getObject("id",UUID.class),rs.getObject("merchant_id",UUID.class),
      rs.getObject("event_id",UUID.class),rs.getString("event_type"),rs.getString("raw_body"),rs.getInt("attempt_count"))).list();
 }
 @Override @Transactional public boolean delivered(UUID id,String owner,int status,Instant at){
   return update(id,owner,"""
    status='DELIVERED',response_status=:status,delivered_at=:at,
    lock_owner=null,lock_until=null,failure_code=null
    """,Map.of("status",status,"at",at.atOffset(ZoneOffset.UTC)));
 }
 @Override @Transactional public boolean retry(UUID id,String owner,int status,String excerpt,Instant next){
   return update(id,owner,"""
    status='PENDING',response_status=:status,response_body_excerpt=:excerpt,
    next_attempt_at=:next,lock_owner=null,lock_until=null
    """,
    Map.of("status",status,"excerpt",safe(excerpt),"next",next.atOffset(ZoneOffset.UTC)));
 }
 @Override @Transactional public boolean dead(UUID id,String owner,Integer status,String excerpt,String code){
  var params=new HashMap<String,Object>();params.put("status",status);params.put("excerpt",safe(excerpt));params.put("code",code);
   return update(id,owner,"""
    status='DEAD',response_status=:status,response_body_excerpt=:excerpt,
    failure_code=:code,lock_owner=null,lock_until=null
    """,params);
 }
 @Override @Transactional public boolean manualRequeue(UUID id,String actor,String correlation,Instant now){
   int changed=jdbc.sql("""
    update notification.webhook_deliveries set status='PENDING',next_attempt_at=:now,
    lock_owner=null,lock_until=null,failure_code=null where id=:id and status='DEAD'
    """)
    .param("id",id).param("now",now.atOffset(ZoneOffset.UTC)).update();
   if(changed==1)jdbc.sql("""
    insert into notification.webhook_audit(id,actor_id,action,delivery_id,correlation_id,created_at)
    values(:audit,:actor,'WEBHOOK_MANUAL_RETRY',:delivery,:correlation,:now)
    """)
    .param("audit",UUID.randomUUID()).param("actor",actor).param("delivery",id).param("correlation",correlation)
    .param("now",now.atOffset(ZoneOffset.UTC)).update();
  return changed==1;
 }
 @Override @Transactional(readOnly=true)
 public WebhookDeadPage findDead(int page,int size){
  if(page<0||size<1||size>100)throw new IllegalArgumentException(
    "webhook dead-letter page must be non-negative and size must be between 1 and 100");
  List<WebhookDeadItem> items=jdbc.sql("""
    select id,merchant_id,event_id,event_type,attempt_count,response_status,
      response_body_excerpt,failure_code,next_attempt_at,created_at
    from notification.webhook_deliveries where status='DEAD'
    order by created_at,id limit :limit offset :offset
    """).param("limit",size).param("offset",Math.multiplyExact(page,size))
    .query((rs,n)->new WebhookDeadItem(
      rs.getObject("id",UUID.class),rs.getObject("merchant_id",UUID.class),
      rs.getObject("event_id",UUID.class),rs.getString("event_type"),rs.getInt("attempt_count"),
      rs.getObject("response_status",Integer.class),rs.getString("response_body_excerpt"),
      rs.getString("failure_code"),rs.getObject("next_attempt_at",OffsetDateTime.class).toInstant(),
      rs.getObject("created_at",OffsetDateTime.class).toInstant())).list();
  long total=jdbc.sql("select count(*) from notification.webhook_deliveries where status='DEAD'")
    .query(Long.class).single();
  return WebhookDeadPage.of(items,page,size,total);
 }
 private boolean update(UUID id,String owner,String assignments,Map<String,?> extra){
  var spec=jdbc.sql("update notification.webhook_deliveries set "+assignments+" where id=:id and status='PROCESSING' and lock_owner=:owner")
    .param("id",id).param("owner",owner);
  for(var e:extra.entrySet())spec=spec.param(e.getKey(),e.getValue());
  return spec.update()==1;
 }
 private static String safe(String value){if(value==null)return "";return value.length()>1000?value.substring(0,1000):value;}
}
