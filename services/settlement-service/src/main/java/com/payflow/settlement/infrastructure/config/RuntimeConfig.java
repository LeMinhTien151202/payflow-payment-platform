package com.payflow.settlement.infrastructure.config;
import java.time.Clock;
import org.springframework.context.annotation.*;
@Configuration(proxyBeanMethods=false) public class RuntimeConfig { @Bean Clock clock(){return Clock.systemUTC();} }
