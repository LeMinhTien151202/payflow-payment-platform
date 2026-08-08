package com.payflow.merchant.infrastructure.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods=false)
class RuntimeConfig { @Bean Clock clock(){ return Clock.systemUTC(); } }
