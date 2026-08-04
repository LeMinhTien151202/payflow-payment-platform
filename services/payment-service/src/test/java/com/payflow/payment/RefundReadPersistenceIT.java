package com.payflow.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.payflow.payment.application.RefundDetail;
import com.payflow.payment.application.exception.RefundNotFoundException;
import com.payflow.payment.application.handler.GetRefundHandler;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** PostgreSQL proof that a refund read cannot cross payment or merchant ownership boundaries. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class RefundReadPersistenceIT extends AbstractPostgresIT {

    @Autowired
    private GetRefundHandler getRefund;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("refund lookup requires matching refund, payment and merchant")
    void scopesRefundToParentAndMerchant() {
        UUID merchantId = UUID.randomUUID();
        UUID paymentId = insertPayment(merchantId);
        UUID refundId = insertRefund(paymentId, merchantId);

        RefundDetail detail = getRefund.handle(refundId, paymentId, merchantId);

        assertThat(detail.refundId()).isEqualTo(refundId);
        assertThat(detail.paymentId()).isEqualTo(paymentId);
        assertThat(detail.status().name()).isEqualTo("CREATED");
        assertThatThrownBy(() -> getRefund.handle(refundId, paymentId, UUID.randomUUID()))
                .isInstanceOf(RefundNotFoundException.class);
        assertThatThrownBy(() -> getRefund.handle(refundId, UUID.randomUUID(), merchantId))
                .isInstanceOf(RefundNotFoundException.class);
    }

    private UUID insertPayment(UUID merchantId) {
        UUID paymentId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO payment.payments (id, merchant_id, customer_id, source_account_id,"
                        + " merchant_reference, idempotency_key, amount, currency, status,"
                        + " created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, 100.0000, 'VND',"
                        + " 'SUCCEEDED', now(), now())",
                paymentId,
                merchantId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                "REFUND-READ-REF-" + paymentId,
                "REFUND-READ-KEY-" + paymentId);
        return paymentId;
    }

    private UUID insertRefund(UUID paymentId, UUID merchantId) {
        UUID refundId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO payment.refunds (id, payment_id, merchant_id, idempotency_key, amount,"
                        + " currency, reason, requested_by, status, created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, 25.0000, 'VND', 'Customer request', 'test-actor',"
                        + " 'CREATED', now(), now())",
                refundId,
                paymentId,
                merchantId,
                "REFUND-READ-IDEMPOTENCY-" + refundId);
        return refundId;
    }
}
