package com.payflow.payment.infrastructure.recovery;

import com.payflow.payment.application.saga.PaymentSagaRecoveryPolicy;
import com.payflow.payment.application.saga.ApplyRiskAssessmentPolicy;
import com.payflow.payment.application.saga.PaymentFinalizationPolicy;
import com.payflow.payment.application.saga.PaymentFundsReservationPolicy;
import com.payflow.payment.application.saga.SagaRecoverySettings;
import com.payflow.payment.application.refund.RefundFinalizationPolicy;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires pure recovery policy/settings while keeping infrastructure values outside the domain. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SagaRecoveryProperties.class)
class SagaRecoveryConfig {

    @Bean
    ApplyRiskAssessmentPolicy applyRiskAssessmentPolicy() {
        return new ApplyRiskAssessmentPolicy();
    }

    @Bean
    PaymentFundsReservationPolicy paymentFundsReservationPolicy() {
        return new PaymentFundsReservationPolicy();
    }

    @Bean
    PaymentFinalizationPolicy paymentFinalizationPolicy() {
        return new PaymentFinalizationPolicy();
    }

    @Bean
    RefundFinalizationPolicy refundFinalizationPolicy() {
        return new RefundFinalizationPolicy();
    }

    @Bean
    PaymentSagaRecoveryPolicy paymentSagaRecoveryPolicy() {
        return new PaymentSagaRecoveryPolicy();
    }

    @Bean
    SagaRecoverySettings sagaRecoverySettings(SagaRecoveryProperties properties) {
        return new SagaRecoverySettings(
                properties.stepTimeout(), properties.maxRetries(), properties.batchSize());
    }
}
