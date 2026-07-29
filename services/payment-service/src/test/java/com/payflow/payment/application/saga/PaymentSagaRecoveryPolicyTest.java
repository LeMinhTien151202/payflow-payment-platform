package com.payflow.payment.application.saga;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.payflow.events.account.AccountFundsReleasedData;
import com.payflow.events.account.AccountFundsReservedData;
import com.payflow.events.ledger.LedgerPaymentPostingFailedData;
import com.payflow.events.payment.PaymentFailedData;
import com.payflow.payment.application.exception.PaymentSagaContractMismatchException;
import com.payflow.payment.domain.model.MerchantSnapshot;
import com.payflow.payment.domain.model.MerchantStatus;
import com.payflow.payment.domain.model.Money;
import com.payflow.payment.domain.model.Payment;
import com.payflow.payment.domain.model.PaymentIntake;
import com.payflow.payment.domain.model.PaymentSaga;
import com.payflow.payment.domain.model.PaymentSagaStatus;
import com.payflow.payment.domain.model.PaymentSagaStep;
import com.payflow.payment.domain.model.PaymentStatus;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PaymentSagaRecoveryPolicyTest {

    private static final Instant CREATED = Instant.parse("2026-07-28T11:00:00Z");
    private static final Instant DEADLINE = CREATED.plusSeconds(30);
    private static final Instant NEXT_DEADLINE = CREATED.plusSeconds(60);
    private static final UUID PAYMENT_ID =
            UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
    private static final UUID ACCOUNT_ID =
            UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
    private static final UUID RESERVATION_ID =
            UUID.fromString("cccccccc-cccc-4ccc-8ccc-cccccccccccc");
    private static final UUID JOURNAL_ID =
            UUID.fromString("dddddddd-dddd-4ddd-8ddd-dddddddddddd");

    private final PaymentSagaRecoveryPolicy policy = new PaymentSagaRecoveryPolicy();

    @Test
    void notYetDueSagaIsANoOp() {
        Fixture fixture = atPostLedger();

        SagaRecoveryAction result = policy.onDeadline(
                fixture.payment(),
                fixture.saga(),
                fixture.reservation(),
                DEADLINE.minusMillis(1),
                NEXT_DEADLINE,
                3);

        assertThat(result).isInstanceOf(SagaRecoveryAction.NoAction.class);
        assertThat(fixture.saga().retryCount()).isZero();
    }

    @Test
    void overdueStepRetriesOnlyUpToConfiguredLimit() {
        Fixture fixture = atPostLedger();

        SagaRecoveryAction result = policy.onDeadline(
                fixture.payment(),
                fixture.saga(),
                fixture.reservation(),
                DEADLINE,
                NEXT_DEADLINE,
                1);

        assertThat(result).isInstanceOf(SagaRecoveryAction.RetryStep.class);
        assertThat(fixture.saga().retryCount()).isEqualTo(1);
        assertThat(fixture.saga().currentStep()).isEqualTo(PaymentSagaStep.POST_LEDGER);
        assertThat(fixture.payment().status()).isEqualTo(PaymentStatus.PROCESSING);
    }

    @Test
    void exhaustedLedgerTimeoutBeginsReleaseExactlyBeforeJournalFact() {
        Fixture fixture = atPostLedger();

        SagaRecoveryAction result = policy.onDeadline(
                fixture.payment(),
                fixture.saga(),
                fixture.reservation(),
                DEADLINE,
                NEXT_DEADLINE,
                0);

        assertThat(result).isInstanceOf(SagaRecoveryAction.ReleaseFunds.class);
        SagaRecoveryAction.ReleaseFunds release = (SagaRecoveryAction.ReleaseFunds) result;
        assertThat(release.command().reservationId()).isEqualTo(RESERVATION_ID);
        assertThat(release.command().reasonCode())
                .isEqualTo(PaymentSagaRecoveryPolicy.LEDGER_RETRY_EXHAUSTED);
        assertThat(fixture.saga().status()).isEqualTo(PaymentSagaStatus.COMPENSATING);
        assertThat(fixture.payment().status()).isEqualTo(PaymentStatus.PROCESSING);
    }

    @Test
    void captureTimeoutAfterJournalEntersManualReviewAndNeverReleases() {
        Fixture fixture = atPostLedger();
        fixture.saga().recordLedgerPosted(
                JOURNAL_ID, DEADLINE, CREATED.plusSeconds(3));

        SagaRecoveryAction result = policy.onDeadline(
                fixture.payment(),
                fixture.saga(),
                fixture.reservation(),
                DEADLINE,
                NEXT_DEADLINE,
                0);

        assertThat(result).isInstanceOf(SagaRecoveryAction.ManualReview.class);
        assertThat(fixture.saga().status())
                .isEqualTo(PaymentSagaStatus.MANUAL_REVIEW_REQUIRED);
        assertThat(fixture.saga().journalId()).isEqualTo(JOURNAL_ID);
        assertThat(fixture.payment().status())
                .isEqualTo(PaymentStatus.MANUAL_REVIEW_REQUIRED);
    }

    @Test
    void permanentLedgerFailureBeginsCompensationWithoutRetry() {
        Fixture fixture = atPostLedger();
        LedgerPaymentPostingFailedData failure = new LedgerPaymentPostingFailedData(
                PAYMENT_ID, "LEDGER_JOURNAL_REJECTED", CREATED.plusSeconds(4));

        SagaRecoveryAction result = policy.onLedgerPostingFailed(
                fixture.payment(),
                fixture.saga(),
                fixture.reservation(),
                failure,
                false,
                CREATED.plusSeconds(4),
                NEXT_DEADLINE,
                3);

        assertThat(result).isInstanceOf(SagaRecoveryAction.ReleaseFunds.class);
        assertThat(fixture.saga().status()).isEqualTo(PaymentSagaStatus.COMPENSATING);
        assertThat(fixture.saga().retryCount()).isZero();
    }

    @Test
    void releaseAcknowledgementCompletesCompensationAndFailsPayment() {
        Fixture fixture = atPostLedger();
        policy.onDeadline(
                fixture.payment(),
                fixture.saga(),
                fixture.reservation(),
                DEADLINE,
                NEXT_DEADLINE,
                0);
        AccountFundsReleasedData released = new AccountFundsReleasedData(
                PAYMENT_ID,
                ACCOUNT_ID,
                RESERVATION_ID,
                fixture.reservation().amount(),
                fixture.reservation().currency(),
                PaymentSagaRecoveryPolicy.LEDGER_RETRY_EXHAUSTED,
                CREATED.plusSeconds(40));

        PaymentFailedData result = policy.onFundsReleased(
                fixture.payment(),
                fixture.saga(),
                fixture.reservation(),
                released,
                CREATED.plusSeconds(40));

        assertThat(result.failureCode())
                .isEqualTo(PaymentSagaRecoveryPolicy.LEDGER_POSTING_FAILED);
        assertThat(fixture.saga().status()).isEqualTo(PaymentSagaStatus.COMPENSATED);
        assertThat(fixture.payment().status()).isEqualTo(PaymentStatus.FAILED);
    }

    @Test
    void mismatchedReleaseCannotAdvanceEitherAggregate() {
        Fixture fixture = atPostLedger();
        policy.onDeadline(
                fixture.payment(),
                fixture.saga(),
                fixture.reservation(),
                DEADLINE,
                NEXT_DEADLINE,
                0);
        AccountFundsReleasedData wrong = new AccountFundsReleasedData(
                PAYMENT_ID,
                UUID.randomUUID(),
                RESERVATION_ID,
                fixture.reservation().amount(),
                fixture.reservation().currency(),
                PaymentSagaRecoveryPolicy.LEDGER_RETRY_EXHAUSTED,
                CREATED.plusSeconds(40));

        assertThatThrownBy(() -> policy.onFundsReleased(
                        fixture.payment(),
                        fixture.saga(),
                        fixture.reservation(),
                        wrong,
                        CREATED.plusSeconds(40)))
                .isInstanceOf(PaymentSagaContractMismatchException.class)
                .hasMessageContaining("accountId");
        assertThat(fixture.saga().status()).isEqualTo(PaymentSagaStatus.COMPENSATING);
        assertThat(fixture.payment().status()).isEqualTo(PaymentStatus.PROCESSING);
    }

    @Test
    void releaseWithAReasonDifferentFromTheRequestedCompensationIsRejected() {
        Fixture fixture = atPostLedger();
        policy.onDeadline(
                fixture.payment(),
                fixture.saga(),
                fixture.reservation(),
                DEADLINE,
                NEXT_DEADLINE,
                0);
        AccountFundsReleasedData wrong = new AccountFundsReleasedData(
                PAYMENT_ID,
                ACCOUNT_ID,
                RESERVATION_ID,
                fixture.reservation().amount(),
                fixture.reservation().currency(),
                "DIFFERENT_COMPENSATION",
                CREATED.plusSeconds(40));

        assertThatThrownBy(() -> policy.onFundsReleased(
                        fixture.payment(),
                        fixture.saga(),
                        fixture.reservation(),
                        wrong,
                        CREATED.plusSeconds(40)))
                .isInstanceOf(PaymentSagaContractMismatchException.class)
                .hasMessageContaining("reasonCode");
        assertThat(fixture.saga().status()).isEqualTo(PaymentSagaStatus.COMPENSATING);
        assertThat(fixture.payment().status()).isEqualTo(PaymentStatus.PROCESSING);
    }

    private static Fixture atPostLedger() {
        Payment payment = Payment.rehydrateLegacyNoFee(
                PAYMENT_ID,
                UUID.fromString("eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"),
                new PaymentIntake(
                        PAYMENT_ID,
                        UUID.fromString("ffffffff-ffff-4fff-8fff-ffffffffffff"),
                        ACCOUNT_ID,
                        "ORDER-RECOVERY",
                        "recovery-idempotency-key",
                        Money.of("500000", "VND"),
                        null,
                        Map.of(),
                        CREATED),
                PaymentStatus.PROCESSING,
                CREATED.plusSeconds(2));
        PaymentSaga saga = PaymentSaga.start(
                UUID.randomUUID(), PAYMENT_ID, CREATED.plusSeconds(10), CREATED);
        saga.recordRiskApproved(
                CREATED.plusSeconds(20), CREATED.plusSeconds(1));
        saga.recordFundsReserved(
                RESERVATION_ID, DEADLINE, CREATED.plusSeconds(2));
        AccountFundsReservedData reservation = new AccountFundsReservedData(
                PAYMENT_ID,
                ACCOUNT_ID,
                RESERVATION_ID,
                payment.amount().amount(),
                payment.amount().currency());
        return new Fixture(payment, saga, reservation);
    }

    private record Fixture(
            Payment payment, PaymentSaga saga, AccountFundsReservedData reservation) {}
}
