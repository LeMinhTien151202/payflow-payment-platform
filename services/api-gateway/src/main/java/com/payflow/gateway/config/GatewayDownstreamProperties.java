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
        @DefaultValue("http://localhost:8081") String paymentService,
        @DefaultValue("http://localhost:8087") String merchantService,
        @DefaultValue("http://localhost:8085") String notificationService,
        @DefaultValue("http://localhost:8088") String reportingService,
        @DefaultValue("http://localhost:8089") String settlementService) {
}
