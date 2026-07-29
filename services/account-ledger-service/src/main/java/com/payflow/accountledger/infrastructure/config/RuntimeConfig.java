package com.payflow.accountledger.infrastructure.config;

import com.payflow.accountledger.account.application.refund.RefundCreditPolicy;
import com.payflow.accountledger.account.application.reservation.CaptureFundsPolicy;
import com.payflow.accountledger.account.application.reservation.ReleaseFundsPolicy;
import com.payflow.accountledger.account.application.reservation.ReserveFundsPolicy;
import com.payflow.accountledger.ledger.application.payment.PaymentJournalFactory;
import com.payflow.accountledger.ledger.application.refund.RefundJournalFactory;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class RuntimeConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    RefundCreditPolicy refundCreditPolicy() {
        return new RefundCreditPolicy();
    }

    @Bean
    RefundJournalFactory refundJournalFactory() {
        return new RefundJournalFactory();
    }

    @Bean
    ReserveFundsPolicy reserveFundsPolicy() {
        return new ReserveFundsPolicy();
    }

    @Bean
    CaptureFundsPolicy captureFundsPolicy() {
        return new CaptureFundsPolicy();
    }

    @Bean
    ReleaseFundsPolicy releaseFundsPolicy() {
        return new ReleaseFundsPolicy();
    }

    @Bean
    PaymentJournalFactory paymentJournalFactory() {
        return new PaymentJournalFactory();
    }
}
