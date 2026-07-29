package com.payflow.payment.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.payflow.payment.domain.exception.UnexpectedRefundStatusException;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RefundTest {

    private static final UUID REFUND_ID = UUID.fromString("73817fe8-219a-4136-921c-2473c1ea9e9b");
    private static final Instant CREATED = Instant.parse("2026-07-29T09:00:00Z");

    @Test
    void createStartsInCreatedAndKeepsAuditActor() {
        Refund refund = refund();

        assertThat(refund.status()).isEqualTo(RefundStatus.CREATED);
        assertThat(refund.requestedBy()).isEqualTo("merchant-user-42");
        assertThat(refund.completedAt()).isNull();
        assertThat(refund.feeReversalAmount()).isNull();
    }

    @Test
    void processingCanSucceedWithExplicitFeeReversal() {
        Refund refund = refund();
        refund.startProcessing(CREATED.plusSeconds(1));
        refund.succeed(Money.of("4", "VND"), CREATED.plusSeconds(2));

        assertThat(refund.status()).isEqualTo(RefundStatus.SUCCEEDED);
        assertThat(refund.feeReversalAmount()).isEqualTo(Money.of("4", "VND"));
        assertThat(refund.completedAt()).isEqualTo(CREATED.plusSeconds(2));
    }

    @Test
    void definitiveFailureHasStableCodeAndNoFeeReversal() {
        Refund refund = refund();
        refund.fail("ACCOUNT_REFUND_REJECTED", CREATED.plusSeconds(1));

        assertThat(refund.status()).isEqualTo(RefundStatus.FAILED);
        assertThat(refund.failureCode()).isEqualTo("ACCOUNT_REFUND_REJECTED");
        assertThat(refund.feeReversalAmount()).isNull();
    }

    @Test
    void terminalRefundCannotMoveAgain() {
        Refund refund = refund();
        refund.fail("ACCOUNT_REFUND_REJECTED", CREATED.plusSeconds(1));

        assertThatThrownBy(() -> refund.startProcessing(CREATED.plusSeconds(2)))
                .isInstanceOf(UnexpectedRefundStatusException.class);
    }

    private static Refund refund() {
        return Refund.create(
                REFUND_ID,
                UUID.fromString("c73e17b5-aaca-48da-9ed5-bb0937499f01"),
                UUID.fromString("11111111-1111-4111-8111-111111111111"),
                "refund-key-1",
                Money.of("200", "VND"),
                "Khách trả hàng",
                "merchant-user-42",
                CREATED);
    }
}
