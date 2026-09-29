package com.payflow.notification.infrastructure.delivery;

import com.payflow.notification.application.webhook.*;
import com.payflow.security.OutboundHttpUrlPolicy;
import java.net.URI;
import java.time.Clock;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.web.client.*;

public final class HttpWebhookTransport implements WebhookTransport {
 private final RestClient rest;private final WebhookProperties props;private final Clock clock;
 public HttpWebhookTransport(RestClient rest,WebhookProperties props,Clock clock){
  this.rest=rest;this.props=props;this.clock=clock;
 }
 public Result send(UUID merchantId,UUID eventId,String eventType,String rawBody){
  try{
   URI url;
   try {
    url=OutboundHttpUrlPolicy.requireAllowed(
      props.endpointUri().toString(),props.allowUnsafeLocalTargets());
   } catch(IllegalArgumentException unsafeTarget) {
    return new Result(400,"webhook target blocked by security policy",false);
   }
   long timestamp=clock.instant().getEpochSecond();
   var response=rest.post().uri(url).contentType(MediaType.APPLICATION_JSON)
    .header("X-PayFlow-Event-Id",eventId.toString()).header("X-PayFlow-Timestamp",Long.toString(timestamp))
    .header("X-PayFlow-Event-Type",eventType)
    .header("X-PayFlow-Merchant-Id",merchantId.toString())
    .header("X-PayFlow-Signature",WebhookSignature.sign(props.signingSecret(),timestamp,rawBody))
    .body(rawBody).retrieve().toEntity(String.class);
   return new Result(response.getStatusCode().value(),safe(response.getBody()),false);
  }catch(HttpStatusCodeException ex){return new Result(ex.getStatusCode().value(),safe(ex.getResponseBodyAsString()),false);}
   catch(ResourceAccessException ex){return new Result(null,"network failure",true);}
 }
 private static String safe(String v){if(v==null)return "";return v.length()>1000?v.substring(0,1000):v;}
}
