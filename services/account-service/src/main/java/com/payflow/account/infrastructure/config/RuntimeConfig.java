package com.payflow.account.infrastructure.config;

import com.payflow.account.application.refund.RefundCreditPolicy;
import com.payflow.account.application.reservation.CaptureFundsPolicy;
import com.payflow.account.application.reservation.ReleaseFundsPolicy;
import com.payflow.account.application.reservation.ReserveFundsPolicy;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class RuntimeConfig {
    @Bean Clock clock() { return Clock.systemUTC(); }
    @Bean RefundCreditPolicy refundCreditPolicy() { return new RefundCreditPolicy(); }
    @Bean ReserveFundsPolicy reserveFundsPolicy() { return new ReserveFundsPolicy(); }
    @Bean CaptureFundsPolicy captureFundsPolicy() { return new CaptureFundsPolicy(); }
    @Bean ReleaseFundsPolicy releaseFundsPolicy() { return new ReleaseFundsPolicy(); }
}
