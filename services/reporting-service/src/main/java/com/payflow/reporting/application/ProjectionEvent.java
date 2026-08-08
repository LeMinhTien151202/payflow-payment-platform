package com.payflow.reporting.application;
import java.time.Instant;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
public record ProjectionEvent(UUID eventId,String eventType,int eventVersion,String aggregateId,
 Instant occurredAt,JsonNode data,String rawPayload){}
