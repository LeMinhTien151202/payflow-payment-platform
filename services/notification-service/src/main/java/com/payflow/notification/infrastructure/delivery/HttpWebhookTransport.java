package com.payflow.notification.infrastructure.delivery;

import com.payflow.notification.application.webhook.*;
import java.time.Clock;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.web.client.*;
import tools.jackson.databind.ObjectMapper;

public final class HttpWebhookTransport implements WebhookTransport {
 private final RestClient rest;private final WebhookProperties props;private final ClientCredentialsTokenProvider tokens;private final ObjectMapper json;private final Clock clock;
 public HttpWebhookTransport(RestClient rest,WebhookProperties props,ObjectMapper json,Clock clock){
  this.rest=rest;this.props=props;this.json=json;this.clock=clock;this.tokens=new ClientCredentialsTokenProvider(rest,props,json,clock);
 }
 public Result send(UUID merchantId,UUID eventId,String eventType,String rawBody){
  try{
   String config=rest.get().uri(props.merchantServiceUri().resolve("/internal/v1/merchants/"+merchantId+"/webhook"))
    .header("Authorization","Bearer "+tokens.token()).retrieve().body(String.class);
   var configJson=json.readTree(config);
   if(!configJson.get("enabled").asBoolean())return new Result(410,"webhook disabled",false);
   var subscriptions=json.readTree(configJson.required("subscribedEvents").asText());
   boolean subscribed=subscriptions.isArray()&&subscriptions.valueStream()
    .anyMatch(node->eventType.equals(node.asText()));
   if(!subscribed)return new Result(204,"event type not subscribed",false);
   String url=configJson.get("url").asText();String secret=configJson.get("signingSecret").asText();long timestamp=clock.instant().getEpochSecond();
   var response=rest.post().uri(url).contentType(MediaType.APPLICATION_JSON)
    .header("X-PayFlow-Event-Id",eventId.toString()).header("X-PayFlow-Timestamp",Long.toString(timestamp))
    .header("X-PayFlow-Signature",WebhookSignature.sign(secret,timestamp,rawBody)).body(rawBody).retrieve().toEntity(String.class);
   return new Result(response.getStatusCode().value(),safe(response.getBody()),false);
  }catch(HttpStatusCodeException ex){return new Result(ex.getStatusCode().value(),safe(ex.getResponseBodyAsString()),false);}
   catch(ResourceAccessException ex){return new Result(null,"network failure",true);}
 }
 private static String safe(String v){if(v==null)return "";return v.length()>1000?v.substring(0,1000):v;}
}
