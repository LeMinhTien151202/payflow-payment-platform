package com.payflow.payment.application.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.payflow.payment.domain.model.PaymentSagaStatus;
import com.payflow.payment.domain.model.PaymentSagaStep;
import com.payflow.payment.domain.model.PaymentStatus;
import java.util.Collection;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class AuditRecordTest {

    @Test
    @DisplayName("serialized review facts expose only the ADR-022 allowlist")
    void serializesOnlyAllowlistedFacts() {
        PaymentReviewAuditFacts facts = new PaymentReviewAuditFacts(
                PaymentStatus.MANUAL_REVIEW_REQUIRED,
                PaymentSagaStatus.MANUAL_REVIEW_REQUIRED,
                PaymentSagaStep.CAPTURE_FUNDS,
                null,
                null,
                3,
                "CAPTURE_RETRY_EXHAUSTED");

        Collection<String> names = JsonMapper.builder().build().valueToTree(facts).propertyNames();

        assertThat(names).containsExactlyInAnyOrder(
                "paymentStatus",
                "sagaStatus",
                "sagaStep",
                "reservationId",
                "journalId",
                "retryCount",
                "stableReasonCode");
        assertThat(names).noneMatch(name -> name.matches(
                "(?i).*token.*|.*secret.*|.*password.*|.*api.?key.*|.*email.*|.*ip.*"));
    }

    @Test
    @DisplayName("free-form reason text is rejected instead of being redacted after persistence")
    void rejectsFreeFormReason() {
        assertThatThrownBy(() -> new PaymentReviewAuditFacts(
                        PaymentStatus.MANUAL_REVIEW_REQUIRED,
                        PaymentSagaStatus.MANUAL_REVIEW_REQUIRED,
                        PaymentSagaStep.CAPTURE_FUNDS,
                        null,
                        null,
                        1,
                        "token=do-not-store"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
