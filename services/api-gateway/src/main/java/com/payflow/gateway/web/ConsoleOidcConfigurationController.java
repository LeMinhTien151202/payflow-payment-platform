package com.payflow.gateway.web;

import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Publishes non-secret OIDC coordinates required by the static local browser console. */
@RestController
final class ConsoleOidcConfigurationController {

    private final String issuerUri;
    private final String clientId;

    ConsoleOidcConfigurationController(
            @Value("${payflow.console.oidc.issuer-uri}") String issuerUri,
            @Value("${payflow.console.oidc.client-id}") String clientId) {
        this.issuerUri = issuerUri;
        this.clientId = clientId;
    }

    @GetMapping("/console/oidc-config")
    Map<String, String> oidcConfiguration() {
        return Map.of("issuerUri", issuerUri, "clientId", clientId);
    }
}
