package com.payflow.reporting.api;
import com.payflow.reporting.application.port.ProjectionStore;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/v1/operations/reporting")
@Tag(name="Reporting operations",description="Audited projection recovery operations")
class ReportingOperationsController{
 private final ProjectionStore store;private final Clock clock;
 ReportingOperationsController(ProjectionStore store,Clock clock){this.store=store;this.clock=clock;}
 @Operation(summary="Rebuild reporting projection",description="Replays the durable event log into a new generation, verifies its fingerprint and atomically activates it.")
 @PostMapping("/rebuild") @ResponseStatus(HttpStatus.ACCEPTED)
 Map<String,Object> rebuild(@AuthenticationPrincipal Jwt jwt,@RequestHeader(value="X-Correlation-Id",required=false)String correlation){
  String c=correlation==null||correlation.isBlank()?UUID.randomUUID().toString():correlation;
  UUID generation;
  try{generation=store.rebuild(jwt.getSubject(),c,clock.instant());}
  catch(IllegalStateException failure){throw new ReportingApiException(
    "REPORTING_REBUILD_MISMATCH","Rebuilt projection differs from active projection",409);}
  return Map.of("generationId",generation,"status","ACTIVE");
 }
}
