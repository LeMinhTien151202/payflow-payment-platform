package com.payflow.ledger.infrastructure.config;

import com.payflow.ledger.application.payment.PaymentJournalFactory;
import com.payflow.ledger.application.refund.RefundJournalFactory;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class RuntimeConfig {
    @Bean Clock clock() { return Clock.systemUTC(); }
    @Bean PaymentJournalFactory paymentJournalFactory() { return new PaymentJournalFactory(); }
    @Bean RefundJournalFactory refundJournalFactory() { return new RefundJournalFactory(); }
}
