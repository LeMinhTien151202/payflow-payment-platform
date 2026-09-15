package com.payflow.payment.infrastructure.merchant;

import java.time.Clock;
import java.time.Instant;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

final class MerchantServiceTokenProvider {
    private final RestClient rest;
    private final MerchantClientProperties properties;
    private final ObjectMapper json;
    private final Clock clock;
    private String token;
    private Instant refreshAt = Instant.EPOCH;

    MerchantServiceTokenProvider(
            RestClient rest,
            MerchantClientProperties properties,
            ObjectMapper json,
            Clock clock) {
        this.rest = rest;
        this.properties = properties;
        this.json = json;
        this.clock = clock;
    }

    synchronized String token() {
        if (token != null && clock.instant().isBefore(refreshAt)) {
            return token;
        }
        var form = new LinkedMultiValueMap<String, String>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", properties.clientId());
        form.add("client_secret", properties.clientSecret());
        String response = rest.post()
                .uri(properties.tokenUri())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(String.class);
        var body = json.readTree(response);
        token = body.required("access_token").stringValue();
        long expiresIn = Math.max(30, body.required("expires_in").asLong());
        refreshAt = clock.instant().plusSeconds(Math.max(1, expiresIn - 15));
        return token;
    }
}
