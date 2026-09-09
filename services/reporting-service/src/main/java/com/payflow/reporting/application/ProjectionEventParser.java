package com.payflow.reporting.application;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
@Component
public final class ProjectionEventParser{
 private final ObjectMapper json;
 public ProjectionEventParser(ObjectMapper json){this.json=json;}
 public ProjectionEvent parse(String key,String payload){
  var node=json.readTree(payload);String aggregate=node.required("aggregateId").asText();
  if(key==null||!key.equals(aggregate))throw new IllegalArgumentException("Kafka key must equal aggregateId");
  int version=node.required("eventVersion").asInt();String eventType=node.required("eventType").asText();
  boolean supported=version==1||(version==2&&"payment.succeeded".equals(eventType));
  if(!supported)throw new IllegalArgumentException("Unsupported reporting event contract: "+eventType+" v"+version);
  return new ProjectionEvent(UUID.fromString(node.required("eventId").asText()),eventType,
    version,aggregate,Instant.parse(node.required("occurredAt").asText()),node.required("data"),payload);
 }
}
