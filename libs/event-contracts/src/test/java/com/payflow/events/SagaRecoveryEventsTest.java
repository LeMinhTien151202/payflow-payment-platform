package com.payflow.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.payflow.events.account.AccountEvents;
import com.payflow.events.account.AccountFundsReleasedData;
import com.payflow.events.account.AccountReleaseRequestedData;
import com.payflow.events.ledger.LedgerEvents;
import com.payflow.events.ledger.LedgerPaymentPostingFailedData;
import com.payflow.events.payment.PaymentEvents;
import com.payflow.events.payment.PaymentManualReviewRequiredData;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SagaRecoveryEventsTest {

    private static final UUID PAYMENT_ID =
            UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
    private static final UUID ACCOUNT_ID =
            UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
    private static final UUID RESERVATION_ID =
            UUID.fromString("cccccccc-cccc-4ccc-8ccc-cccccccccccc");
    private static final Instant NOW = Instant.parse("2026-07-28T11:00:00Z");

    @Test
    void recoveryEventTypesAreVersionedPaymentAggregates() {
        assertThat(AccountEvents.RELEASE_REQUESTED.name()).isEqualTo("account.release.requested");
        assertThat(AccountEvents.FUNDS_RELEASED.name()).isEqualTo("account.funds-released");
        assertThat(LedgerEvents.PAYMENT_POSTING_FAILED.name())
                .isEqualTo("ledger.payment-posting-failed");
        assertThat(PaymentEvents.MANUAL_REVIEW_REQUIRED.name())
                .isEqualTo("payment.manual-review-required");
        assertThat(AccountEvents.RELEASE_REQUESTED.version()).isEqualTo(1);
        assertThat(PaymentEvents.MANUAL_REVIEW_REQUIRED.aggregateType()).isEqualTo("PAYMENT");
    }

    @Test
    void releaseCommandAndOutcomeNormalizeMoney() {
        AccountReleaseRequestedData command = new AccountReleaseRequestedData(
                PAYMENT_ID,
                ACCOUNT_ID,
                RESERVATION_ID,
                new BigDecimal("500000"),
                "VND",
                "LEDGER_RETRY_EXHAUSTED");
        AccountFundsReleasedData outcome = new AccountFundsReleasedData(
                PAYMENT_ID,
                ACCOUNT_ID,
                RESERVATION_ID,
                new BigDecimal("500000"),
                "VND",
                "LEDGER_RETRY_EXHAUSTED",
                NOW);

        assertThat(command.amount()).isEqualByComparingTo("500000.0000");
        assertThat(outcome.amount()).isEqualByComparingTo(command.amount());
        assertThat(outcome.releasedAt()).isEqualTo(NOW);
    }

    @Test
    void recoveryCodesMustBeStableUppercaseValues() {
        assertThatThrownBy(() -> new LedgerPaymentPostingFailedData(PAYMENT_ID, "timeout", NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("failureCode");
        assertThatThrownBy(() -> new PaymentManualReviewRequiredData(
                        PAYMENT_ID, "capture-funds", "CAPTURE_TIMEOUT"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sagaStep");
    }

    @Test
    void manualReviewCarriesOnlyPaymentStepAndReason() {
        PaymentManualReviewRequiredData data = new PaymentManualReviewRequiredData(
                PAYMENT_ID, "CAPTURE_FUNDS", "CAPTURE_RETRY_EXHAUSTED");

        assertThat(data.paymentId()).isEqualTo(PAYMENT_ID);
        assertThat(data.sagaStep()).isEqualTo("CAPTURE_FUNDS");
        assertThat(data.reasonCode()).isEqualTo("CAPTURE_RETRY_EXHAUSTED");
    }
}
