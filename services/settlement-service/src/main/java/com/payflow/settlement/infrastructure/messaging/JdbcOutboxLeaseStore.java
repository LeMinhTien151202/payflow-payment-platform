package com.payflow.settlement.infrastructure.messaging;
import com.payflow.settlement.application.outbox.ClaimedOutboxEvent;
import com.payflow.settlement.application.port.OutboxLeaseStore;
import java.sql.*; import java.time.*; import java.util.*;
import org.springframework.jdbc.core.namedparam.*; import org.springframework.stereotype.Component; import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference; import tools.jackson.databind.ObjectMapper;
@Component class JdbcOutboxLeaseStore implements OutboxLeaseStore {
 private static final TypeReference<Map<String,String>> HEADERS=new TypeReference<>(){};
 private static final String RETURNING=" returning id,aggregate_id,topic,payload::text payload,headers::text headers,attempt_count,created_at";
 private final NamedParameterJdbcTemplate jdbc; private final ObjectMapper mapper;
 JdbcOutboxLeaseStore(NamedParameterJdbcTemplate jdbc,ObjectMapper mapper){this.jdbc=jdbc;this.mapper=mapper;}
 @Transactional public List<ClaimedOutboxEvent> claim(String owner,Duration lease,int size){
  var result=new ArrayList<ClaimedOutboxEvent>(); result.addAll(claim("PROCESSING",owner,lease,size,true));
  if(result.size()<size)result.addAll(claim("PENDING",owner,lease,size-result.size(),false)); result.sort(Comparator.comparing(ClaimedOutboxEvent::createdAt)); return List.copyOf(result);
 }
 private List<ClaimedOutboxEvent> claim(String status,String owner,Duration lease,int limit,boolean reclaimed){
  String due=status.equals("PROCESSING")?"lock_until < clock_timestamp()":"next_attempt_at <= clock_timestamp()";
  String sql="update settlement_runtime.outbox_events set status='PROCESSING',lock_owner=:owner,lock_until=clock_timestamp()+make_interval(secs => :seconds),attempt_count=attempt_count+1 where id in (select id from settlement_runtime.outbox_events where status='"+status+"' and "+due+" order by created_at limit :limit for update skip locked)"+RETURNING;
  return jdbc.query(sql,new MapSqlParameterSource().addValue("owner",owner).addValue("seconds",lease.toSeconds()).addValue("limit",limit),(rs,n)->map(rs,reclaimed));
 }
 private ClaimedOutboxEvent map(ResultSet rs,boolean reclaimed)throws SQLException{return new ClaimedOutboxEvent(rs.getObject("id",UUID.class),rs.getString("aggregate_id"),rs.getString("topic"),rs.getString("payload"),mapper.readValue(rs.getString("headers"),HEADERS),rs.getInt("attempt_count"),rs.getObject("created_at",OffsetDateTime.class).toInstant(),reclaimed);}
 @Transactional public boolean markPublished(UUID id,String owner){return update("status='PUBLISHED',published_at=clock_timestamp(),lock_owner=null,lock_until=null,last_error=null",id,owner,null,null)==1;}
 @Transactional public boolean markRetry(UUID id,String owner,Instant next,String error){return update("status='PENDING',next_attempt_at=:next,lock_owner=null,lock_until=null,last_error=:error",id,owner,next,error)==1;}
 @Transactional public boolean markFailed(UUID id,String owner,String error){return update("status='FAILED',lock_owner=null,lock_until=null,last_error=:error",id,owner,null,error)==1;}
 private int update(String set,UUID id,String owner,Instant next,String error){return jdbc.update("update settlement_runtime.outbox_events set "+set+" where id=:id and status='PROCESSING' and lock_owner=:owner",new MapSqlParameterSource().addValue("id",id).addValue("owner",owner).addValue("next",next==null?null:next.atOffset(ZoneOffset.UTC)).addValue("error",error));}
 public double oldestPendingAgeSeconds(){Double v=jdbc.getJdbcTemplate().queryForObject("select coalesce(extract(epoch from(clock_timestamp()-min(created_at))),0) from settlement_runtime.outbox_events where status='PENDING' and next_attempt_at<=clock_timestamp()",Double.class);return v==null?0:Math.max(0,v);}
}
