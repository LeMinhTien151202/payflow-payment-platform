package com.payflow.notification.api;

import com.payflow.notification.application.port.WebhookDeliveryStore;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Clock;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/operations/webhooks")
@Tag(name="Webhook operations",description="Audited recovery for terminal webhook deliveries")
class WebhookOperationsController {
 private final WebhookDeliveryStore store;private final Clock clock;
 WebhookOperationsController(WebhookDeliveryStore store,Clock clock){this.store=store;this.clock=clock;}
 @Operation(summary="Retry a dead webhook",description="Moves only a DEAD delivery back to PENDING while preserving event id and exact raw body.")
 @PostMapping("/{deliveryId}/retry") @ResponseStatus(HttpStatus.ACCEPTED)
 void retry(@PathVariable UUID deliveryId,@AuthenticationPrincipal Jwt jwt,
   @RequestHeader(value="X-Correlation-Id",required=false)String correlation){
  String value=correlation==null||correlation.isBlank()?UUID.randomUUID().toString():correlation;
  if(!store.manualRequeue(deliveryId,jwt.getSubject(),value,clock.instant()))
   throw new WebhookOperationException("WEBHOOK_NOT_RETRYABLE","Webhook delivery is not DEAD or does not exist",409);
 }
}
