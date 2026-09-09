package com.payflow.settlement.infrastructure.persistence;

import com.payflow.events.*;
import com.payflow.settlement.application.port.OutboxAppender;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.*;
import tools.jackson.databind.ObjectMapper;

@Component
class JdbcOutboxAppender implements OutboxAppender {
    private final NamedParameterJdbcTemplate jdbc; private final ObjectMapper mapper;
    JdbcOutboxAppender(NamedParameterJdbcTemplate jdbc,ObjectMapper mapper){this.jdbc=jdbc;this.mapper=mapper;}
    @Override @Transactional(propagation=Propagation.MANDATORY)
    public void append(EventType type,String topic,String aggregateId,String correlationId,Instant occurredAt,Object data){
        UUID id=UUID.randomUUID();
        var envelope=EventEnvelope.of(id,type,aggregateId,correlationId,"settlement-service",occurredAt,data);
        var headers=Map.of(EventHeaders.CORRELATION_ID,correlationId,EventHeaders.EVENT_ID,id.toString(),EventHeaders.EVENT_TYPE,type.name(),EventHeaders.EVENT_VERSION,Integer.toString(type.version()));
        jdbc.update("""
            insert into settlement_runtime.outbox_events(id,aggregate_type,aggregate_id,event_type,event_version,topic,payload,headers,status,attempt_count,next_attempt_at,created_at)
            values(:id,:aggregateType,:aggregateId,:eventType,:version,:topic,cast(:payload as jsonb),cast(:headers as jsonb),'PENDING',0,:at,:at)
            """,new MapSqlParameterSource().addValue("id",id).addValue("aggregateType",type.aggregateType()).addValue("aggregateId",aggregateId)
            .addValue("eventType",type.name()).addValue("version",type.version()).addValue("topic",topic)
            .addValue("payload",mapper.writeValueAsString(envelope)).addValue("headers",mapper.writeValueAsString(headers)).addValue("at",occurredAt.atOffset(ZoneOffset.UTC)));
    }
}
