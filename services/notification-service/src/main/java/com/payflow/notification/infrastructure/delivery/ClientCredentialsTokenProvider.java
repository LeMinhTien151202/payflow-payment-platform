package com.payflow.notification.infrastructure.delivery;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.*;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

final class ClientCredentialsTokenProvider {
 private final RestClient rest;private final WebhookProperties props;private final ObjectMapper json;private final Clock clock;
 private volatile Token cached;
 ClientCredentialsTokenProvider(RestClient rest,WebhookProperties props,ObjectMapper json,Clock clock){this.rest=rest;this.props=props;this.json=json;this.clock=clock;}
 synchronized String token(){
  if(cached!=null&&clock.instant().isBefore(cached.expiresAt().minusSeconds(10)))return cached.value();
  String body="grant_type=client_credentials&client_id="+enc(props.clientId())+"&client_secret="+enc(props.clientSecret());
  String response=rest.post().uri(props.tokenUri()).contentType(MediaType.APPLICATION_FORM_URLENCODED).body(body).retrieve().body(String.class);
  var node=json.readTree(response);String value=node.get("access_token").asText();long seconds=node.get("expires_in").asLong();
  cached=new Token(value,clock.instant().plusSeconds(seconds));return value;
 }
 private static String enc(String value){return URLEncoder.encode(value,StandardCharsets.UTF_8);}
 private record Token(String value,Instant expiresAt){}
}
