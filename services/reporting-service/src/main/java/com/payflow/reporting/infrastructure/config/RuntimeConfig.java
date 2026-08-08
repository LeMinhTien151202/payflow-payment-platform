package com.payflow.reporting.infrastructure.config;
import java.time.Clock;
import org.springframework.context.annotation.*;
@Configuration(proxyBeanMethods=false)
class RuntimeConfig{@Bean Clock clock(){return Clock.systemUTC();}}
