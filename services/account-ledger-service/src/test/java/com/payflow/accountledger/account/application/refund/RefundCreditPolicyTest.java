package com.payflow.accountledger.account.application.refund;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.payflow.accountledger.account.domain.exception.AccountInvariantViolationException;
import com.payflow.accountledger.account.domain.model.Account;
import com.payflow.accountledger.account.domain.model.Money;
import com.payflow.events.EventEnvelope;
import com.payflow.events.account.AccountEvents;
import com.payflow.events.account.AccountRefundCreditRequestedData;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RefundCreditPolicyTest {

    private static final UUID ACCOUNT_ID = UUID.fromString("039bedb6-b2d6-47df-aa25-2035e39136a3");
    private static final UUID PAYMENT_ID = UUID.fromString("c73e17b5-aaca-48da-9ed5-bb0937499f01");
    private static final UUID REFUND_ID = UUID.fromString("73817fe8-219a-4136-921c-2473c1ea9e9b");
    private static final Instant NOW = Instant.parse("2026-07-29T12:00:00Z");

    private final RefundCreditPolicy policy = new RefundCreditPolicy();

    @Test
    void creditsFrozenAccountAndReturnsIdempotencyFact() {
        Account account = account();
        account.freeze();

        RefundCreditResult result = policy.credit(
                account, null, command("200"), UUID.randomUUID(), NOW);

        assertThat(account.availableBalance().amount()).isEqualByComparingTo("1200");
        assertThat(result.duplicate()).isFalse();
        assertThat(result.credit().refundId()).isEqualTo(REFUND_ID);
        assertThat(result.eventData().amount()).isEqualByComparingTo("200");
    }

    @Test
    void newCreditOutcomeKeepsCorrelationAndCausation() {
        Account account = account();
        RefundCreditResult result = policy.credit(
                account, null, command("200"), UUID.randomUUID(), NOW);
        EventEnvelope<AccountRefundCreditRequestedData> cause = EventEnvelope.of(
                UUID.randomUUID(),
                AccountEvents.REFUND_CREDIT_REQUESTED,
                PAYMENT_ID.toString(),
                "corr-refund-credit-success",
                "payment-service",
                NOW,
                command("200"));

        var outcome = new AccountRefundCreditedEventFactory()
                .credited(UUID.randomUUID(), cause, result, NOW.plusSeconds(1));

        assertThat(outcome.eventType()).isEqualTo(AccountEvents.REFUND_CREDITED.name());
        assertThat(outcome.causationId()).isEqualTo(cause.eventId().toString());
        assertThat(outcome.correlationId()).isEqualTo(cause.correlationId());
        assertThat(outcome.data().creditId()).isEqualTo(result.credit().id());
    }

    @Test
    void duplicateSameIntentDoesNotCreditTwiceOrEmitAnotherOutcome() {
        Account account = account();
        RefundCreditResult first = policy.credit(
                account, null, command("200"), UUID.randomUUID(), NOW);

        RefundCreditResult replay = policy.credit(
                account,
                first.credit(),
                command("200.0000"),
                UUID.randomUUID(),
                NOW.plusSeconds(1));

        assertThat(account.availableBalance().amount()).isEqualByComparingTo("1200");
        assertThat(replay.duplicate()).isTrue();
        assertThat(replay.credit()).isEqualTo(first.credit());
        EventEnvelope<AccountRefundCreditRequestedData> cause = EventEnvelope.of(
                UUID.randomUUID(),
                AccountEvents.REFUND_CREDIT_REQUESTED,
                PAYMENT_ID.toString(),
                "corr-refund-credit",
                "payment-service",
                NOW,
                command("200"));
        assertThatThrownBy(() -> new AccountRefundCreditedEventFactory()
                        .credited(UUID.randomUUID(), cause, replay, NOW.plusSeconds(1)))
                .isInstanceOf(AccountInvariantViolationException.class)
                .hasMessageContaining("duplicate");
    }

    @Test
    void sameRefundWithDifferentIntentIsRejectedWithoutMutation() {
        Account account = account();
        RefundCreditResult first = policy.credit(
                account, null, command("200"), UUID.randomUUID(), NOW);

        assertThatThrownBy(() -> policy.credit(
                        account,
                        first.credit(),
                        command("201"),
                        UUID.randomUUID(),
                        NOW.plusSeconds(1)))
                .isInstanceOf(AccountInvariantViolationException.class)
                .hasMessageContaining("does not match");
        assertThat(account.availableBalance().amount()).isEqualByComparingTo("1200");
    }

    @Test
    void closedAccountRequiresRecoveryAndIsNotCredited() {
        Account account = account();
        account.close();

        assertThatThrownBy(() -> policy.credit(
                        account, null, command("200"), UUID.randomUUID(), NOW))
                .isInstanceOf(AccountInvariantViolationException.class)
                .hasMessageContaining("closed");
        assertThat(account.availableBalance().amount()).isEqualByComparingTo("1000");
    }

    private static Account account() {
        return Account.open(ACCOUNT_ID, new Money(new BigDecimal("1000"), "VND"));
    }

    private static AccountRefundCreditRequestedData command(String amount) {
        return new AccountRefundCreditRequestedData(
                REFUND_ID,
                PAYMENT_ID,
                ACCOUNT_ID,
                UUID.fromString("3f93e522-42e6-4c3f-9099-9ded706aec77"),
                new BigDecimal(amount),
                "VND");
    }
}
