package com.payflow.reporting.application.port;
import com.payflow.reporting.application.ProjectionEvent;
import java.time.Instant;
import java.util.*;
public interface ProjectionStore{
 boolean appendAndProject(ProjectionEvent event);
 UUID activeGeneration();
 UUID rebuild(String actor,String correlationId,Instant now);
 List<DailyMetric> daily(UUID merchantId,Instant from,Instant to);
 record DailyMetric(java.time.LocalDate date,String currency,long total,long succeeded,long failed,
  java.math.BigDecimal gross,java.math.BigDecimal refunded){}
}
