package com.payflow.settlement.application;

import java.time.ZoneId;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "payflow.settlement")
public record SettlementRuntimeProperties(
        @DefaultValue("Asia/Ho_Chi_Minh") ZoneId businessZone) {
}
