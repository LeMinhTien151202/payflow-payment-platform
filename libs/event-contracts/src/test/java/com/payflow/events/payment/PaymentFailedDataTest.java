package com.payflow.events.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PaymentFailedDataTest {

    private static final UUID PAYMENT_ID =
            UUID.fromString("c73e17b5-aaca-48da-9ed5-bb0937499f01");
    private static final Instant FAILED_AT = Instant.parse("2026-07-28T08:00:02Z");

    @Test
    void carriesTheStableRiskRejectionCode() {
        PaymentFailedData data =
                new PaymentFailedData(PAYMENT_ID, "RISK_REJECTED", FAILED_AT);

        assertThat(data.paymentId()).isEqualTo(PAYMENT_ID);
        assertThat(data.failureCode()).isEqualTo("RISK_REJECTED");
        assertThat(data.failedAt()).isEqualTo(FAILED_AT);
    }

    @Test
    void rejectsMissingIdentityAndTimestamp() {
        assertThatThrownBy(() -> new PaymentFailedData(null, "RISK_REJECTED", FAILED_AT))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("paymentId");
        assertThatThrownBy(() -> new PaymentFailedData(PAYMENT_ID, "RISK_REJECTED", null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("failedAt");
    }

    @Test
    void rejectsBlankOrOversizedFailureCode() {
        assertThatThrownBy(() -> new PaymentFailedData(PAYMENT_ID, " ", FAILED_AT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("failureCode");
        assertThatThrownBy(() -> new PaymentFailedData(
                        PAYMENT_ID, "X".repeat(PaymentFailedData.MAX_FAILURE_CODE_LENGTH + 1), FAILED_AT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("failureCode");
    }
}
