package com.payflow.notification.application.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.payflow.events.EventEnvelope;
import com.payflow.events.payment.PaymentEvents;
import com.payflow.events.payment.PaymentFailedData;
import com.payflow.events.payment.PaymentSucceededData;
import com.payflow.events.refund.RefundEvents;
import com.payflow.events.refund.RefundFailedData;
import com.payflow.events.refund.RefundSucceededData;
import com.payflow.notification.domain.exception.NotificationInvariantViolationException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OutcomeNotificationFactoryTest {

    private static final Instant NOW = Instant.parse("2026-07-30T02:00:00Z");
    private static final UUID PAYMENT_ID = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID REFUND_ID = UUID.fromString("20000000-0000-4000-8000-000000000001");
    private static final UUID MERCHANT_ID = UUID.fromString("30000000-0000-4000-8000-000000000001");
    private static final UUID CUSTOMER_ID = UUID.fromString("40000000-0000-4000-8000-000000000001");

    private final OutcomeNotificationFactory factory = new OutcomeNotificationFactory();

    @Test
    void mapsPaymentSucceededToCustomerWithoutLosingMoneyScale() {
        var data = new PaymentSucceededData(PAYMENT_ID, MERCHANT_ID, CUSTOMER_ID,
                new BigDecimal("125.5000"), "VND", NOW);
        var event = EventEnvelope.of(UUID.randomUUID(), PaymentEvents.PAYMENT_SUCCEEDED,
                PAYMENT_ID.toString(), "notification-factory", "payment-service", NOW, data);

        var intent = factory.paymentSucceeded(event);

        assertThat(intent.businessReferenceType()).isEqualTo("PAYMENT_OUTCOME");
        assertThat(intent.recipientType()).isEqualTo("CUSTOMER");
        assertThat(intent.recipientId()).isEqualTo(CUSTOMER_ID.toString());
        assertThat(intent.templateCode()).isEqualTo("PAYMENT_SUCCEEDED");
        assertThat(intent.payload()).containsEntry("amount", "125.5000");
    }

    @Test
    void mapsPaymentFailedToTruthfulPaymentRoutingKey() {
        var data = new PaymentFailedData(PAYMENT_ID, "ACCOUNT_CAPTURE_REJECTED", NOW);
        var event = EventEnvelope.of(UUID.randomUUID(), PaymentEvents.PAYMENT_FAILED,
                PAYMENT_ID.toString(), "notification-factory", "payment-service", NOW, data);

        var intent = factory.paymentFailed(event);

        assertThat(intent.recipientType()).isEqualTo("PAYMENT");
        assertThat(intent.recipientId()).isEqualTo(PAYMENT_ID.toString());
        assertThat(intent.payload()).containsEntry("failureCode", "ACCOUNT_CAPTURE_REJECTED");
    }

    @Test
    void mapsBothRefundOutcomesToMerchant() {
        var succeededData = new RefundSucceededData(REFUND_ID, PAYMENT_ID, MERCHANT_ID,
                UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("20.0000"),
                new BigDecimal("1.0000"), "VND", NOW);
        var failedData = new RefundFailedData(REFUND_ID, PAYMENT_ID, MERCHANT_ID,
                new BigDecimal("20.0000"), "VND", "CAPACITY_EXCEEDED", NOW);
        var succeeded = EventEnvelope.of(UUID.randomUUID(), RefundEvents.REFUND_SUCCEEDED,
                PAYMENT_ID.toString(), "notification-factory", "payment-service", NOW, succeededData);
        var failed = EventEnvelope.of(UUID.randomUUID(), RefundEvents.REFUND_FAILED,
                PAYMENT_ID.toString(), "notification-factory", "payment-service", NOW, failedData);

        assertThat(factory.refundSucceeded(succeeded).recipientId())
                .isEqualTo(MERCHANT_ID.toString());
        assertThat(factory.refundFailed(failed).recipientId())
                .isEqualTo(MERCHANT_ID.toString());
    }

    @Test
    void rejectsPayloadIdentityThatDoesNotMatchOrderingAggregate() {
        var data = new PaymentFailedData(PAYMENT_ID, "FAILED", NOW);
        var event = EventEnvelope.of(UUID.randomUUID(), PaymentEvents.PAYMENT_FAILED,
                UUID.randomUUID().toString(), "notification-factory", "payment-service", NOW, data);

        assertThatThrownBy(() -> factory.paymentFailed(event))
                .isInstanceOf(NotificationInvariantViolationException.class)
                .hasMessageContaining("aggregateId");
    }
}
