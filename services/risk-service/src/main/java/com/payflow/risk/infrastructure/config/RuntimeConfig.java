package com.payflow.risk.infrastructure.config;

import com.payflow.risk.application.event.RiskAssessmentEventFactory;
import com.payflow.risk.domain.policy.RiskRuleEngine;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RiskVelocityProperties.class)
class RuntimeConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    RiskRuleEngine riskRuleEngine() {
        return new RiskRuleEngine();
    }

    @Bean
    RiskAssessmentEventFactory riskAssessmentEventFactory() {
        return new RiskAssessmentEventFactory();
    }
}
