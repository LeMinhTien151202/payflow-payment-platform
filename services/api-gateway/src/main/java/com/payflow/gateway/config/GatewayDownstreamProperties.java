package com.payflow.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Base URIs of the services behind the gateway.
 *
 * @param paymentService base URI of payment-service, without a trailing path
 */
@ConfigurationProperties(prefix = "payflow.gateway.downstream")
public record GatewayDownstreamProperties(
        @DefaultValue("http://localhost:8081") String paymentService) {
}
