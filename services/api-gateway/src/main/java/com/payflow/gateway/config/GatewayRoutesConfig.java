package com.payflow.gateway.config;

import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Public route table.
 *
 * <p>Routes are declared in Java rather than YAML on purpose: the property prefix for gateway routes
 * has moved between Spring Cloud Gateway generations, while {@link RouteLocatorBuilder} is stable.
 * It also lets the downstream base URI come from configuration, so a test can point a route at a
 * stub without rewriting the route definition.
 *
 * <p>Only {@code /api/v1/**} is exposed here. {@code /internal/v1/**} is deliberately absent:
 * ARCHITECTURE.md requires internal endpoints to stay off the public edge.
 */
@Configuration(proxyBeanMethods = false)
public class GatewayRoutesConfig {

    @Bean
    RouteLocator payflowRoutes(RouteLocatorBuilder builder, GatewayDownstreamProperties downstream) {
        return builder.routes()
                .route("payment-service", r -> r
                        .path("/api/v1/payments/**")
                        .uri(downstream.paymentService()))
                .build();
    }
}
