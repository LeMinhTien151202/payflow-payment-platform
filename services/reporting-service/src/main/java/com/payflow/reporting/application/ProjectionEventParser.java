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
  int version=node.required("eventVersion").asInt();
  if(version!=1)throw new IllegalArgumentException("Unsupported reporting event version: "+version);
  return new ProjectionEvent(UUID.fromString(node.required("eventId").asText()),node.required("eventType").asText(),
    version,aggregate,Instant.parse(node.required("occurredAt").asText()),node.required("data"),payload);
 }
}
