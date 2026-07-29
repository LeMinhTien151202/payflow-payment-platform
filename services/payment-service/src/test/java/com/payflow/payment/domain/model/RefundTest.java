package com.payflow.payment.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.payflow.payment.domain.exception.UnexpectedRefundStatusException;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RefundTest {

    private static final UUID REFUND_ID = UUID.fromString("73817fe8-219a-4136-921c-2473c1ea9e9b");
    private static final UUID JOURNAL_ID = UUID.fromString("3f93e522-42e6-4c3f-9099-9ded706aec77");
    private static final UUID CREDIT_ID = UUID.fromString("e99ff96f-4df3-4f2a-9433-c9aba292786c");
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
        refund.startProcessing(JOURNAL_ID, CREATED.plusSeconds(1));
        refund.succeed(CREDIT_ID, Money.of("4", "VND"), CREATED.plusSeconds(2));

        assertThat(refund.status()).isEqualTo(RefundStatus.SUCCEEDED);
        assertThat(refund.feeReversalAmount()).isEqualTo(Money.of("4", "VND"));
        assertThat(refund.ledgerJournalId()).isEqualTo(JOURNAL_ID);
        assertThat(refund.accountCreditId()).isEqualTo(CREDIT_ID);
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

        assertThatThrownBy(() -> refund.startProcessing(JOURNAL_ID, CREATED.plusSeconds(2)))
                .isInstanceOf(UnexpectedRefundStatusException.class);
        assertThat(refund.ledgerJournalId()).isNull();
    }

    @Test
    void invalidSuccessDoesNotLeakCreditIdentityIntoAggregate() {
        Refund refund = refund();
        refund.startProcessing(JOURNAL_ID, CREATED.plusSeconds(1));

        assertThatThrownBy(() -> refund.succeed(CREDIT_ID, null, CREATED.plusSeconds(2)))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("feeReversal");
        assertThat(refund.status()).isEqualTo(RefundStatus.PROCESSING);
        assertThat(refund.accountCreditId()).isNull();
        assertThat(refund.feeReversalAmount()).isNull();
    }

    @Test
    void postedJournalCannotBeConvertedToAutomaticFailure() {
        Refund refund = refund();
        refund.startProcessing(JOURNAL_ID, CREATED.plusSeconds(1));

        assertThatThrownBy(() -> refund.fail("ACCOUNT_CREDIT_TIMEOUT", CREATED.plusSeconds(2)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("journal");
        assertThat(refund.status()).isEqualTo(RefundStatus.PROCESSING);
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
