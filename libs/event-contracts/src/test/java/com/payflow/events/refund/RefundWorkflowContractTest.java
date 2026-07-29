package com.payflow.events.refund;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.payflow.events.account.AccountEvents;
import com.payflow.events.account.AccountRefundCreditRequestedData;
import com.payflow.events.account.AccountRefundCreditedData;
import com.payflow.events.ledger.LedgerEvents;
import com.payflow.events.ledger.LedgerRefundPostedData;
import com.payflow.events.ledger.LedgerRefundPostingFailedData;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class RefundWorkflowContractTest {

    private static final UUID REFUND_ID = UUID.fromString("73817fe8-219a-4136-921c-2473c1ea9e9b");
    private static final UUID PAYMENT_ID = UUID.fromString("c73e17b5-aaca-48da-9ed5-bb0937499f01");
    private static final UUID MERCHANT_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID ACCOUNT_ID = UUID.fromString("039bedb6-b2d6-47df-aa25-2035e39136a3");
    private static final UUID JOURNAL_ID = UUID.fromString("3f93e522-42e6-4c3f-9099-9ded706aec77");
    private static final UUID CREDIT_ID = UUID.fromString("e99ff96f-4df3-4f2a-9433-c9aba292786c");
    private static final Instant AT = Instant.parse("2026-07-29T12:00:00Z");

    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    void eventNamesVersionsAndOrderingAggregateAreStable() {
        assertType(LedgerEvents.REFUND_POSTED, "ledger.refund-posted");
        assertType(LedgerEvents.REFUND_POSTING_FAILED, "ledger.refund-posting-failed");
        assertType(AccountEvents.REFUND_CREDIT_REQUESTED, "account.refund-credit.requested");
        assertType(AccountEvents.REFUND_CREDITED, "account.refund-credited");
        assertType(RefundEvents.REFUND_SUCCEEDED, "refund.succeeded");
        assertType(RefundEvents.REFUND_FAILED, "refund.failed");
    }

    @Test
    void ledgerPayloadsHaveExactV1Shape() {
        assertThat(mapper.valueToTree(posted()).propertyNames())
                .containsExactlyInAnyOrder(
                        "refundId", "paymentId", "journalId", "accountId", "amount", "currency");
        assertThat(mapper.valueToTree(new LedgerRefundPostingFailedData(
                                REFUND_ID,
                                PAYMENT_ID,
                                new BigDecimal("40"),
                                "VND",
                                "LEDGER_ACCOUNT_NOT_FOUND"))
                        .propertyNames())
                .containsExactlyInAnyOrder(
                        "refundId", "paymentId", "amount", "currency", "failureCode");
    }

    @Test
    void accountPayloadsHaveExactV1Shape() {
        assertThat(mapper.valueToTree(requestCredit()).propertyNames())
                .containsExactlyInAnyOrder(
                        "refundId", "paymentId", "accountId", "journalId", "amount", "currency");
        assertThat(mapper.valueToTree(credited()).propertyNames())
                .containsExactlyInAnyOrder(
                        "refundId",
                        "paymentId",
                        "accountId",
                        "journalId",
                        "creditId",
                        "amount",
                        "currency");
    }

    @Test
    void terminalRefundPayloadsHaveExactV1Shape() {
        assertThat(mapper.valueToTree(new RefundSucceededData(
                                REFUND_ID,
                                PAYMENT_ID,
                                MERCHANT_ID,
                                JOURNAL_ID,
                                CREDIT_ID,
                                new BigDecimal("40"),
                                new BigDecimal("0.8"),
                                "VND",
                                AT))
                        .propertyNames())
                .containsExactlyInAnyOrder(
                        "refundId",
                        "paymentId",
                        "merchantId",
                        "journalId",
                        "creditId",
                        "amount",
                        "feeReversalAmount",
                        "currency",
                        "completedAt");
        assertThat(mapper.valueToTree(new RefundFailedData(
                                REFUND_ID,
                                PAYMENT_ID,
                                MERCHANT_ID,
                                new BigDecimal("40"),
                                "VND",
                                "LEDGER_ACCOUNT_NOT_FOUND",
                                AT))
                        .propertyNames())
                .containsExactlyInAnyOrder(
                        "refundId",
                        "paymentId",
                        "merchantId",
                        "amount",
                        "currency",
                        "failureCode",
                        "failedAt");
    }

    @Test
    void allMoneyFieldsRejectInvalidScaleOrSign() {
        assertThatThrownBy(() -> new LedgerRefundPostedData(
                        REFUND_ID,
                        PAYMENT_ID,
                        JOURNAL_ID,
                        ACCOUNT_ID,
                        new BigDecimal("0"),
                        "VND"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RefundSucceededData(
                        REFUND_ID,
                        PAYMENT_ID,
                        MERCHANT_ID,
                        JOURNAL_ID,
                        CREDIT_ID,
                        new BigDecimal("40"),
                        new BigDecimal("0.00001"),
                        "VND",
                        AT))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static void assertType(com.payflow.events.EventType type, String name) {
        assertThat(type.name()).isEqualTo(name);
        assertThat(type.version()).isEqualTo(1);
        assertThat(type.aggregateType()).isEqualTo("PAYMENT");
    }

    private static LedgerRefundPostedData posted() {
        return new LedgerRefundPostedData(
                REFUND_ID,
                PAYMENT_ID,
                JOURNAL_ID,
                ACCOUNT_ID,
                new BigDecimal("40"),
                "VND");
    }

    private static AccountRefundCreditRequestedData requestCredit() {
        return new AccountRefundCreditRequestedData(
                REFUND_ID,
                PAYMENT_ID,
                ACCOUNT_ID,
                JOURNAL_ID,
                new BigDecimal("40"),
                "VND");
    }

    private static AccountRefundCreditedData credited() {
        return new AccountRefundCreditedData(
                REFUND_ID,
                PAYMENT_ID,
                ACCOUNT_ID,
                JOURNAL_ID,
                CREDIT_ID,
                new BigDecimal("40"),
                "VND");
    }
}
