package com.payflow.payment.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.payflow.events.EventEnvelope;
import com.payflow.events.EventType;
import com.payflow.events.account.AccountEvents;
import com.payflow.events.account.AccountFundsCapturedData;
import com.payflow.events.account.AccountRefundCreditedData;
import com.payflow.events.ledger.LedgerEvents;
import com.payflow.events.ledger.LedgerPaymentPostedData;
import com.payflow.events.ledger.LedgerRefundPostedData;
import com.payflow.events.risk.RiskAssessmentCompletedData;
import com.payflow.events.risk.RiskDecisionValue;
import com.payflow.events.risk.RiskEvents;
import com.payflow.events.risk.RiskLevelValue;
import com.payflow.payment.application.handler.HandlePaymentWorkflowEventHandler;
import com.payflow.payment.application.handler.HandleRefundWorkflowEventHandler;
import com.payflow.payment.application.inbox.EventProcessingResult;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class PaymentWorkflowEventRouterTest {

    private static final UUID PAYMENT_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID ACCOUNT_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID RESERVATION_ID = UUID.fromString("30000000-0000-0000-0000-000000000001");
    private static final UUID JOURNAL_ID = UUID.fromString("40000000-0000-0000-0000-000000000001");
    private static final Instant OCCURRED_AT = Instant.parse("2026-07-29T02:00:00Z");

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final HandlePaymentWorkflowEventHandler handler =
            mock(HandlePaymentWorkflowEventHandler.class);
    private final HandleRefundWorkflowEventHandler refundHandler =
            mock(HandleRefundWorkflowEventHandler.class);
    private final PaymentWorkflowEventRouter router =
            new PaymentWorkflowEventRouter(mapper, handler, refundHandler);

    @Test
    void routesRiskAccountAndLedgerContractsToTheirTypedHandler() {
        var risk = envelope(
                RiskEvents.RISK_ASSESSMENT_COMPLETED,
                new RiskAssessmentCompletedData(
                        PAYMENT_ID,
                        RiskDecisionValue.APPROVED,
                        10,
                        RiskLevelValue.LOW,
                        List.of(),
                        "rules-v1"));
        var capture = envelope(
                AccountEvents.FUNDS_CAPTURED,
                new AccountFundsCapturedData(
                        PAYMENT_ID,
                        ACCOUNT_ID,
                        RESERVATION_ID,
                        new BigDecimal("500000.0000"),
                        "VND",
                        OCCURRED_AT));
        var ledger = envelope(
                LedgerEvents.PAYMENT_POSTED,
                new LedgerPaymentPostedData(
                        PAYMENT_ID,
                        JOURNAL_ID,
                        new BigDecimal("500000.0000"),
                        "VND"));
        when(handler.handleRiskAssessment(risk)).thenReturn(EventProcessingResult.PROCESSED);
        when(handler.handleFundsCaptured(capture)).thenReturn(EventProcessingResult.DUPLICATE);
        when(handler.handleLedgerPosted(ledger)).thenReturn(EventProcessingResult.PROCESSED);

        assertThat(router.route(PAYMENT_ID.toString(), mapper.writeValueAsString(risk)))
                .isEqualTo(PaymentWorkflowEventRouter.RouteResult.PROCESSED);
        assertThat(router.route(PAYMENT_ID.toString(), mapper.writeValueAsString(capture)))
                .isEqualTo(PaymentWorkflowEventRouter.RouteResult.DUPLICATE);
        assertThat(router.route(PAYMENT_ID.toString(), mapper.writeValueAsString(ledger)))
                .isEqualTo(PaymentWorkflowEventRouter.RouteResult.PROCESSED);

        verify(handler).handleRiskAssessment(risk);
        verify(handler).handleFundsCaptured(capture);
        verify(handler).handleLedgerPosted(ledger);
    }

    @Test
    void routesRefundLedgerAndAccountOutcomesToRefundHandler() {
        UUID refundId = UUID.randomUUID();
        var ledger = envelope(
                LedgerEvents.REFUND_POSTED,
                new LedgerRefundPostedData(
                        refundId,
                        PAYMENT_ID,
                        JOURNAL_ID,
                        ACCOUNT_ID,
                        new BigDecimal("40"),
                        "VND"));
        var credited = envelope(
                AccountEvents.REFUND_CREDITED,
                new AccountRefundCreditedData(
                        refundId,
                        PAYMENT_ID,
                        ACCOUNT_ID,
                        JOURNAL_ID,
                        UUID.randomUUID(),
                        new BigDecimal("40"),
                        "VND"));
        when(refundHandler.handleLedgerRefundPosted(ledger))
                .thenReturn(EventProcessingResult.PROCESSED);
        when(refundHandler.handleAccountRefundCredited(credited))
                .thenReturn(EventProcessingResult.DUPLICATE);

        assertThat(router.route(PAYMENT_ID.toString(), mapper.writeValueAsString(ledger)))
                .isEqualTo(PaymentWorkflowEventRouter.RouteResult.PROCESSED);
        assertThat(router.route(PAYMENT_ID.toString(), mapper.writeValueAsString(credited)))
                .isEqualTo(PaymentWorkflowEventRouter.RouteResult.DUPLICATE);

        verify(refundHandler).handleLedgerRefundPosted(ledger);
        verify(refundHandler).handleAccountRefundCredited(credited);
    }

    @Test
    void ignoresOtherBoundedContextEventsOnASubscribedTopic() {
        String payload = mapper.writeValueAsString(Map.of("eventType", "account.balance-adjusted"));

        assertThat(router.route(PAYMENT_ID.toString(), payload))
                .isEqualTo(PaymentWorkflowEventRouter.RouteResult.IGNORED);
        verifyNoInteractions(handler);
        verifyNoInteractions(refundHandler);
    }

    @Test
    void rejectsAKeyThatWouldBreakPerPaymentOrdering() {
        var risk = envelope(
                RiskEvents.RISK_ASSESSMENT_COMPLETED,
                new RiskAssessmentCompletedData(
                        PAYMENT_ID,
                        RiskDecisionValue.APPROVED,
                        10,
                        RiskLevelValue.LOW,
                        List.of(),
                        "rules-v1"));

        assertThatThrownBy(() -> router.route(UUID.randomUUID().toString(), mapper.writeValueAsString(risk)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Kafka key");
        verifyNoInteractions(handler);
        verifyNoInteractions(refundHandler);
    }

    @Test
    void rejectsMalformedPayloadInsteadOfAcknowledgingIt() {
        assertThatThrownBy(() -> router.route(PAYMENT_ID.toString(), "{not-json"))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> router.route(PAYMENT_ID.toString(), "{}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("eventType");
    }

    private static <T> EventEnvelope<T> envelope(EventType type, T data) {
        return EventEnvelope.of(
                UUID.randomUUID(),
                type,
                PAYMENT_ID.toString(),
                "corr-router-1",
                "upstream-service",
                OCCURRED_AT,
                data);
    }
}
