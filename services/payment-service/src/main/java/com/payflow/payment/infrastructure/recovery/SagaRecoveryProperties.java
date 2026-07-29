package com.payflow.payment.infrastructure.recovery;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Type-safe scheduler settings; invalid retry loops fail application startup. */
@ConfigurationProperties(prefix = "payflow.saga-recovery")
record SagaRecoveryProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("1s") Duration pollInterval,
        @DefaultValue("30s") Duration stepTimeout,
        @DefaultValue("3") int maxRetries,
        @DefaultValue("50") int batchSize) {}
