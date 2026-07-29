package com.payflow.accountledger.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.payflow.accountledger.account.application.handler.HandleRefundCreditRequestedHandler;
import com.payflow.accountledger.application.inbox.EventProcessingResult;
import com.payflow.accountledger.ledger.application.handler.HandleRefundRequestedHandler;
import com.payflow.events.EventEnvelope;
import com.payflow.events.EventType;
import com.payflow.events.account.AccountEvents;
import com.payflow.events.account.AccountRefundCreditRequestedData;
import com.payflow.events.refund.RefundEvents;
import com.payflow.events.refund.RefundRequestedData;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class RefundWorkflowEventRouterTest {

    private static final UUID PAYMENT_ID = UUID.fromString("20000000-0000-4000-8000-000000000001");
    private static final UUID REFUND_ID = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID ACCOUNT_ID = UUID.fromString("40000000-0000-4000-8000-000000000001");
    private static final Instant NOW = Instant.parse("2026-07-29T13:00:00Z");

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final HandleRefundRequestedHandler ledger = mock(HandleRefundRequestedHandler.class);
    private final HandleRefundCreditRequestedHandler account =
            mock(HandleRefundCreditRequestedHandler.class);
    private final RefundWorkflowEventRouter router =
            new RefundWorkflowEventRouter(mapper, ledger, account);

    @Test
    void routesBothOwnedRefundCommands() {
        var refund = envelope(RefundEvents.REFUND_REQUESTED, new RefundRequestedData(
                REFUND_ID,
                PAYMENT_ID,
                UUID.randomUUID(),
                UUID.randomUUID(),
                ACCOUNT_ID,
                new BigDecimal("40.0000"),
                "VND",
                NOW));
        var credit = envelope(
                AccountEvents.REFUND_CREDIT_REQUESTED,
                new AccountRefundCreditRequestedData(
                        REFUND_ID,
                        PAYMENT_ID,
                        ACCOUNT_ID,
                        UUID.randomUUID(),
                        new BigDecimal("40.0000"),
                        "VND"));
        when(ledger.handle(refund)).thenReturn(EventProcessingResult.PROCESSED);
        when(account.handle(credit)).thenReturn(EventProcessingResult.BUSINESS_DUPLICATE);

        assertThat(router.route(PAYMENT_ID.toString(), mapper.writeValueAsString(refund)))
                .isEqualTo(RefundWorkflowEventRouter.RouteResult.PROCESSED);
        assertThat(router.route(PAYMENT_ID.toString(), mapper.writeValueAsString(credit)))
                .isEqualTo(RefundWorkflowEventRouter.RouteResult.BUSINESS_DUPLICATE);
        verify(ledger).handle(refund);
        verify(account).handle(credit);
    }

    @Test
    void ignoresUnownedEventWithoutCallingHandlers() {
        String payload = mapper.writeValueAsString(Map.of("eventType", "payment.succeeded"));

        assertThat(router.route(PAYMENT_ID.toString(), payload))
                .isEqualTo(RefundWorkflowEventRouter.RouteResult.IGNORED);
        verifyNoInteractions(ledger, account);
    }

    @Test
    void rejectsKafkaKeyThatBreaksPaymentOrdering() {
        var refund = envelope(RefundEvents.REFUND_REQUESTED, new RefundRequestedData(
                REFUND_ID,
                PAYMENT_ID,
                UUID.randomUUID(),
                UUID.randomUUID(),
                ACCOUNT_ID,
                new BigDecimal("40.0000"),
                "VND",
                NOW));

        assertThatThrownBy(() -> router.route(
                        UUID.randomUUID().toString(), mapper.writeValueAsString(refund)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Kafka key");
        verifyNoInteractions(ledger, account);
    }

    private static <T> EventEnvelope<T> envelope(EventType type, T data) {
        return EventEnvelope.of(
                UUID.randomUUID(),
                type,
                PAYMENT_ID.toString(),
                "corr-router-test",
                "payment-service",
                NOW,
                data);
    }
}
