package com.payflow.payment.infrastructure.messaging;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Bounded listener recovery settings; invalid infinite-style values fail application startup. */
@ConfigurationProperties(prefix = "payflow.workflow-consumer")
record PaymentWorkflowConsumerProperties(
        @DefaultValue("1s") Duration retryBackoff,
        @DefaultValue("3") int maxRetries) {

    PaymentWorkflowConsumerProperties {
        if (retryBackoff == null || retryBackoff.isNegative() || retryBackoff.isZero()) {
            throw new IllegalArgumentException("workflow consumer retryBackoff must be positive");
        }
        if (maxRetries < 0 || maxRetries > 100) {
            throw new IllegalArgumentException("workflow consumer maxRetries must be between 0 and 100");
        }
    }
}
