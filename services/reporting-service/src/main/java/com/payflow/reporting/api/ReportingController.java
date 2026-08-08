package com.payflow.reporting.api;
import com.payflow.reporting.application.port.ProjectionStore;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/v1/reports")
@Tag(name="Reports",description="Merchant-scoped read models built asynchronously from versioned Kafka facts")
class ReportingController{
 private final ProjectionStore store;
 ReportingController(ProjectionStore store){this.store=store;}
 @Operation(summary="Daily payment metrics",description="Reads the active projection generation; this endpoint never joins another service database.")
 @GetMapping("/daily")
 List<ProjectionStore.DailyMetric> daily(@AuthenticationPrincipal Jwt jwt,@RequestParam Instant from,@RequestParam Instant to){
  if(!from.isBefore(to))throw new IllegalArgumentException("from must be before to");
  String merchantClaim=jwt.getClaimAsString("merchant_id");
  if(merchantClaim==null||merchantClaim.isBlank())throw new ReportingApiException(
    "REPORTING_MERCHANT_CONTEXT_REQUIRED","Token has no merchant context",403);
  UUID merchant=UUID.fromString(merchantClaim);
  return store.daily(merchant,from,to);
 }
}
