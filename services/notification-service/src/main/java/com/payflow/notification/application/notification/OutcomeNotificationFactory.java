package com.payflow.notification.application.notification;

import com.payflow.events.EventEnvelope;
import com.payflow.events.EventType;
import com.payflow.events.payment.PaymentEvents;
import com.payflow.events.payment.PaymentFailedData;
import com.payflow.events.payment.PaymentSucceededData;
import com.payflow.events.refund.RefundEvents;
import com.payflow.events.refund.RefundFailedData;
import com.payflow.events.refund.RefundSucceededData;
import com.payflow.notification.domain.exception.NotificationInvariantViolationException;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Objects;

public final class OutcomeNotificationFactory {

    public OutcomeNotificationIntent paymentSucceeded(EventEnvelope<PaymentSucceededData> event) {
        requirePaymentContract(event, PaymentEvents.PAYMENT_SUCCEEDED, event.data().paymentId());
        var data = event.data();
        return intent(event, "PAYMENT_OUTCOME", data.paymentId(), "CUSTOMER",
                data.customerId().toString(), "PAYMENT_SUCCEEDED", Map.of(
                        "paymentId", data.paymentId().toString(),
                        "merchantId", data.merchantId().toString(),
                        "amount", money(data.amount()),
                        "currency", data.currency(),
                        "completedAt", data.completedAt().toString()));
    }

    public OutcomeNotificationIntent paymentFailed(EventEnvelope<PaymentFailedData> event) {
        requirePaymentContract(event, PaymentEvents.PAYMENT_FAILED, event.data().paymentId());
        var data = event.data();
        // payment.failed v1 has no customerId; PAYMENT/paymentId is the only truthful routing key.
        return intent(event, "PAYMENT_OUTCOME", data.paymentId(), "PAYMENT",
                data.paymentId().toString(), "PAYMENT_FAILED", Map.of(
                        "paymentId", data.paymentId().toString(),
                        "failureCode", data.failureCode(),
                        "failedAt", data.failedAt().toString()));
    }

    public OutcomeNotificationIntent refundSucceeded(EventEnvelope<RefundSucceededData> event) {
        requireRefundContract(event, RefundEvents.REFUND_SUCCEEDED, event.data().paymentId());
        var data = event.data();
        return intent(event, "REFUND_OUTCOME", data.refundId(), "MERCHANT",
                data.merchantId().toString(), "REFUND_SUCCEEDED", Map.of(
                        "refundId", data.refundId().toString(),
                        "paymentId", data.paymentId().toString(),
                        "amount", money(data.amount()),
                        "feeReversalAmount", money(data.feeReversalAmount()),
                        "currency", data.currency(),
                        "completedAt", data.completedAt().toString()));
    }

    public OutcomeNotificationIntent refundFailed(EventEnvelope<RefundFailedData> event) {
        requireRefundContract(event, RefundEvents.REFUND_FAILED, event.data().paymentId());
        var data = event.data();
        return intent(event, "REFUND_OUTCOME", data.refundId(), "MERCHANT",
                data.merchantId().toString(), "REFUND_FAILED", Map.of(
                        "refundId", data.refundId().toString(),
                        "paymentId", data.paymentId().toString(),
                        "amount", money(data.amount()),
                        "currency", data.currency(),
                        "failureCode", data.failureCode(),
                        "failedAt", data.failedAt().toString()));
    }

    private static OutcomeNotificationIntent intent(
            EventEnvelope<?> event,
            String referenceType,
            java.util.UUID referenceId,
            String recipientType,
            String recipientId,
            String template,
            Map<String, String> payload) {
        return new OutcomeNotificationIntent(event.eventId(), event.eventType(), event.aggregateId(),
                referenceType, referenceId, recipientType, recipientId, template, payload,
                event.occurredAt());
    }

    private static void requirePaymentContract(
            EventEnvelope<?> event, EventType expected, java.util.UUID paymentId) {
        requireContract(event, expected);
        if (!paymentId.toString().equals(event.aggregateId())) {
            throw new NotificationInvariantViolationException(
                    expected.name() + " aggregateId does not match payload paymentId");
        }
    }

    private static void requireRefundContract(
            EventEnvelope<?> event, EventType expected, java.util.UUID paymentId) {
        requireContract(event, expected);
        if (!paymentId.toString().equals(event.aggregateId())) {
            throw new NotificationInvariantViolationException(
                    expected.name() + " aggregateId does not match payload paymentId");
        }
    }

    private static void requireContract(EventEnvelope<?> event, EventType expected) {
        Objects.requireNonNull(event, "event");
        if (!expected.name().equals(event.eventType())
                || expected.version() != event.eventVersion()
                || !expected.aggregateType().equals(event.aggregateType())) {
            throw new NotificationInvariantViolationException(
                    "notification consumer requires " + expected.name() + " v" + expected.version());
        }
    }

    private static String money(BigDecimal value) {
        return value.toPlainString();
    }
}
