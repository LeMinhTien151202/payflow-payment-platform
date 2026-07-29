package com.payflow.accountledger.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.payflow.accountledger.account.application.handler.HandleCaptureFundsRequestedHandler;
import com.payflow.accountledger.account.application.handler.HandleRefundCreditRequestedHandler;
import com.payflow.accountledger.account.application.handler.HandleReleaseFundsRequestedHandler;
import com.payflow.accountledger.account.application.handler.HandleReserveFundsRequestedHandler;
import com.payflow.accountledger.application.inbox.EventProcessingResult;
import com.payflow.accountledger.ledger.application.handler.HandlePostPaymentRequestedHandler;
import com.payflow.accountledger.ledger.application.handler.HandleRefundRequestedHandler;
import com.payflow.events.EventEnvelope;
import com.payflow.events.EventType;
import com.payflow.events.account.AccountCaptureRequestedData;
import com.payflow.events.account.AccountEvents;
import com.payflow.events.account.AccountRefundCreditRequestedData;
import com.payflow.events.account.AccountReleaseRequestedData;
import com.payflow.events.account.AccountReserveRequestedData;
import com.payflow.events.ledger.LedgerEvents;
import com.payflow.events.ledger.LedgerPostPaymentRequestedData;
import com.payflow.events.refund.RefundEvents;
import com.payflow.events.refund.RefundRequestedData;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class AccountLedgerWorkflowEventRouterTest {

    private static final UUID PAYMENT_ID = UUID.fromString("20000000-0000-4000-8000-000000000001");
    private static final UUID ACCOUNT_ID = UUID.fromString("40000000-0000-4000-8000-000000000001");
    private static final UUID RESERVATION_ID = UUID.fromString("60000000-0000-4000-8000-000000000001");
    private static final UUID REFUND_ID = UUID.fromString("70000000-0000-4000-8000-000000000001");
    private static final Instant NOW = Instant.parse("2026-07-30T01:00:00Z");

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final HandleReserveFundsRequestedHandler reserve =
            mock(HandleReserveFundsRequestedHandler.class);
    private final HandleCaptureFundsRequestedHandler capture =
            mock(HandleCaptureFundsRequestedHandler.class);
    private final HandleReleaseFundsRequestedHandler release =
            mock(HandleReleaseFundsRequestedHandler.class);
    private final HandlePostPaymentRequestedHandler paymentJournal =
            mock(HandlePostPaymentRequestedHandler.class);
    private final HandleRefundRequestedHandler refundJournal =
            mock(HandleRefundRequestedHandler.class);
    private final HandleRefundCreditRequestedHandler refundCredit =
            mock(HandleRefundCreditRequestedHandler.class);
    private final AccountLedgerWorkflowEventRouter router = new AccountLedgerWorkflowEventRouter(
            mapper, reserve, capture, release, paymentJournal, refundJournal, refundCredit);

    @Test
    void routesAccountAndLedgerPaymentCommands() {
        var reserveEvent = envelope(
                AccountEvents.RESERVE_REQUESTED,
                new AccountReserveRequestedData(
                        PAYMENT_ID,
                        ACCOUNT_ID,
                        new BigDecimal("40.0000"),
                        "VND",
                        NOW.plusSeconds(60)));
        var ledgerEvent = envelope(
                LedgerEvents.POST_PAYMENT_REQUESTED,
                new LedgerPostPaymentRequestedData(
                        PAYMENT_ID,
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        new BigDecimal("40.0000"),
                        "VND"));
        when(reserve.handle(reserveEvent)).thenReturn(EventProcessingResult.PROCESSED);
        when(paymentJournal.handle(ledgerEvent))
                .thenReturn(EventProcessingResult.BUSINESS_DUPLICATE);

        assertThat(router.route(PAYMENT_ID.toString(), mapper.writeValueAsString(reserveEvent)))
                .isEqualTo(AccountLedgerWorkflowEventRouter.RouteResult.PROCESSED);
        assertThat(router.route(PAYMENT_ID.toString(), mapper.writeValueAsString(ledgerEvent)))
                .isEqualTo(AccountLedgerWorkflowEventRouter.RouteResult.BUSINESS_DUPLICATE);
        verify(reserve).handle(reserveEvent);
        verify(paymentJournal).handle(ledgerEvent);
    }

    @Test
    void routesFinalizationCompensationAndRefundCommands() {
        var captureEvent = envelope(
                AccountEvents.CAPTURE_REQUESTED,
                new AccountCaptureRequestedData(
                        PAYMENT_ID,
                        ACCOUNT_ID,
                        RESERVATION_ID,
                        new BigDecimal("40.0000"),
                        "VND"));
        var releaseEvent = envelope(
                AccountEvents.RELEASE_REQUESTED,
                new AccountReleaseRequestedData(
                        PAYMENT_ID,
                        ACCOUNT_ID,
                        RESERVATION_ID,
                        new BigDecimal("40.0000"),
                        "VND",
                        "LEDGER_POSTING_FAILED"));
        var refundEvent = envelope(
                RefundEvents.REFUND_REQUESTED,
                new RefundRequestedData(
                        REFUND_ID,
                        PAYMENT_ID,
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        ACCOUNT_ID,
                        new BigDecimal("10.0000"),
                        "VND",
                        NOW));
        var creditEvent = envelope(
                AccountEvents.REFUND_CREDIT_REQUESTED,
                new AccountRefundCreditRequestedData(
                        REFUND_ID,
                        PAYMENT_ID,
                        ACCOUNT_ID,
                        UUID.randomUUID(),
                        new BigDecimal("10.0000"),
                        "VND"));
        when(capture.handle(captureEvent)).thenReturn(EventProcessingResult.PROCESSED);
        when(release.handle(releaseEvent)).thenReturn(EventProcessingResult.PROCESSED);
        when(refundJournal.handle(refundEvent)).thenReturn(EventProcessingResult.PROCESSED);
        when(refundCredit.handle(creditEvent)).thenReturn(EventProcessingResult.PROCESSED);

        assertThat(router.route(PAYMENT_ID.toString(), mapper.writeValueAsString(captureEvent)))
                .isEqualTo(AccountLedgerWorkflowEventRouter.RouteResult.PROCESSED);
        assertThat(router.route(PAYMENT_ID.toString(), mapper.writeValueAsString(releaseEvent)))
                .isEqualTo(AccountLedgerWorkflowEventRouter.RouteResult.PROCESSED);
        assertThat(router.route(PAYMENT_ID.toString(), mapper.writeValueAsString(refundEvent)))
                .isEqualTo(AccountLedgerWorkflowEventRouter.RouteResult.PROCESSED);
        assertThat(router.route(PAYMENT_ID.toString(), mapper.writeValueAsString(creditEvent)))
                .isEqualTo(AccountLedgerWorkflowEventRouter.RouteResult.PROCESSED);
        verify(capture).handle(captureEvent);
        verify(release).handle(releaseEvent);
        verify(refundJournal).handle(refundEvent);
        verify(refundCredit).handle(creditEvent);
    }

    @Test
    void ignoresUnownedEventWithoutCallingHandlers() {
        String payload = mapper.writeValueAsString(Map.of("eventType", "payment.succeeded"));

        assertThat(router.route(PAYMENT_ID.toString(), payload))
                .isEqualTo(AccountLedgerWorkflowEventRouter.RouteResult.IGNORED);
        verifyNoInteractions(
                reserve, capture, release, paymentJournal, refundJournal, refundCredit);
    }

    @Test
    void rejectsKafkaKeyThatBreaksPaymentOrdering() {
        var reserveEvent = envelope(
                AccountEvents.RESERVE_REQUESTED,
                new AccountReserveRequestedData(
                        PAYMENT_ID,
                        ACCOUNT_ID,
                        new BigDecimal("40.0000"),
                        "VND",
                        NOW.plusSeconds(60)));

        assertThatThrownBy(() -> router.route(
                        UUID.randomUUID().toString(), mapper.writeValueAsString(reserveEvent)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Kafka key");
        verifyNoInteractions(
                reserve, capture, release, paymentJournal, refundJournal, refundCredit);
    }

    private static <T> EventEnvelope<T> envelope(EventType type, T data) {
        return EventEnvelope.of(
                UUID.randomUUID(),
                type,
                PAYMENT_ID.toString(),
                "corr-workflow-router-test",
                "payment-service",
                NOW,
                data);
    }
}
