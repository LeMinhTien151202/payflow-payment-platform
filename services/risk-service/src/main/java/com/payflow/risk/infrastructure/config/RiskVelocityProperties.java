package com.payflow.risk.infrastructure.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "payflow.risk-velocity")
public record RiskVelocityProperties(@DefaultValue("2h") Duration retention) {

    public RiskVelocityProperties {
        if (retention == null || retention.compareTo(Duration.ofHours(1)) <= 0) {
            throw new IllegalArgumentException(
                    "payflow.risk-velocity.retention must be greater than one hour");
        }
    }
}
