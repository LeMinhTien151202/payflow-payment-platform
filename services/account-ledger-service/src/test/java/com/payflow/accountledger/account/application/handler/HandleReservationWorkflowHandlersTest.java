package com.payflow.accountledger.account.application.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.payflow.accountledger.account.application.port.AccountReservationStore;
import com.payflow.accountledger.account.application.reservation.CaptureFundsPolicy;
import com.payflow.accountledger.account.application.reservation.ReleaseFundsPolicy;
import com.payflow.accountledger.account.application.reservation.ReserveFundsPolicy;
import com.payflow.accountledger.account.domain.model.Account;
import com.payflow.accountledger.account.domain.model.Money;
import com.payflow.accountledger.account.domain.model.Reservation;
import com.payflow.accountledger.account.domain.model.ReservationStatus;
import com.payflow.accountledger.application.inbox.EventProcessingResult;
import com.payflow.accountledger.application.port.OutboxAppender;
import com.payflow.accountledger.application.port.ProcessedEventStore;
import com.payflow.accountledger.support.TestTransactionManager;
import com.payflow.events.EventEnvelope;
import com.payflow.events.PayFlowTopics;
import com.payflow.events.account.AccountCaptureRequestedData;
import com.payflow.events.account.AccountEvents;
import com.payflow.events.account.AccountReleaseRequestedData;
import com.payflow.events.account.AccountReserveRequestedData;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

class HandleReservationWorkflowHandlersTest {

    private static final UUID PAYMENT_ID = UUID.fromString("20000000-0000-4000-8000-000000000001");
    private static final UUID ACCOUNT_ID = UUID.fromString("40000000-0000-4000-8000-000000000001");
    private static final UUID RESERVATION_ID = UUID.fromString("60000000-0000-4000-8000-000000000001");
    private static final Instant NOW = Instant.parse("2026-07-30T01:00:00Z");

    private final ProcessedEventStore inbox = mock(ProcessedEventStore.class);
    private final AccountReservationStore store = mock(AccountReservationStore.class);
    private final OutboxAppender outbox = mock(OutboxAppender.class);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final TransactionTemplate transactions =
            new TransactionTemplate(new TestTransactionManager());

    @Test
    void reserveMovesAvailableToReservedAndAppendsOutcome() {
        Account account = Account.open(ACCOUNT_ID, Money.of("100.0000", "VND"));
        when(inbox.recordIfNew(any())).thenReturn(true);
        when(store.findAccountForUpdate(ACCOUNT_ID)).thenReturn(Optional.of(account));
        when(store.findReservationByPaymentId(PAYMENT_ID)).thenReturn(Optional.empty());
        var event = reserveEvent();
        var handler = new HandleReserveFundsRequestedHandler(
                inbox, store, outbox, new ReserveFundsPolicy(), clock, transactions);

        assertThat(handler.handle(event)).isEqualTo(EventProcessingResult.PROCESSED);
        assertThat(account.availableBalance()).isEqualTo(Money.of("60.0000", "VND"));
        assertThat(account.reservedBalance()).isEqualTo(Money.of("40.0000", "VND"));
        verify(store).updateAccount(account);
        verify(store).saveReservation(any());
        verify(outbox).appendCausedBy(
                eq(AccountEvents.FUNDS_RESERVED),
                eq(PayFlowTopics.ACCOUNT_EVENTS),
                eq(PAYMENT_ID.toString()),
                eq(NOW),
                any(),
                eq(event));
    }

    @Test
    void reserveTransportDuplicateStopsBeforeAccountLock() {
        when(inbox.recordIfNew(any())).thenReturn(false);
        var handler = new HandleReserveFundsRequestedHandler(
                inbox, store, outbox, new ReserveFundsPolicy(), clock, transactions);

        assertThat(handler.handle(reserveEvent())).isEqualTo(EventProcessingResult.DUPLICATE);

        verify(store, never()).findAccountForUpdate(any());
        verify(outbox, never()).appendCausedBy(any(), any(), any(), any(), any(), any());
    }

    @Test
    void insufficientFundsAppendsStableFailureWithoutBalanceMutation() {
        Account account = Account.open(ACCOUNT_ID, Money.of("20.0000", "VND"));
        when(inbox.recordIfNew(any())).thenReturn(true);
        when(store.findAccountForUpdate(ACCOUNT_ID)).thenReturn(Optional.of(account));
        when(store.findReservationByPaymentId(PAYMENT_ID)).thenReturn(Optional.empty());
        var event = reserveEvent();
        var handler = new HandleReserveFundsRequestedHandler(
                inbox, store, outbox, new ReserveFundsPolicy(), clock, transactions);

        assertThat(handler.handle(event)).isEqualTo(EventProcessingResult.PROCESSED);
        assertThat(account.availableBalance()).isEqualTo(Money.of("20.0000", "VND"));
        assertThat(account.reservedBalance()).isEqualTo(Money.zero("VND"));
        verify(store, never()).updateAccount(any());
        verify(store, never()).saveReservation(any());
        verify(outbox).appendCausedBy(
                eq(AccountEvents.FUNDS_RESERVATION_FAILED),
                eq(PayFlowTopics.ACCOUNT_EVENTS),
                eq(PAYMENT_ID.toString()),
                eq(NOW),
                any(),
                eq(event));
    }

    @Test
    void captureConsumesReservedFundsAndAppendsAcknowledgement() {
        var fixture = reservedFixture();
        when(inbox.recordIfNew(any())).thenReturn(true);
        when(store.findAccountForUpdate(ACCOUNT_ID)).thenReturn(Optional.of(fixture.account()));
        when(store.findReservationForUpdate(RESERVATION_ID))
                .thenReturn(Optional.of(fixture.reservation()));
        var event = captureEvent();
        var handler = new HandleCaptureFundsRequestedHandler(
                inbox, store, outbox, new CaptureFundsPolicy(), clock, transactions);

        assertThat(handler.handle(event)).isEqualTo(EventProcessingResult.PROCESSED);
        assertThat(fixture.account().reservedBalance()).isEqualTo(Money.zero("VND"));
        assertThat(fixture.reservation().status()).isEqualTo(ReservationStatus.CAPTURED);
        verify(store).updateReservation(fixture.reservation());
        verify(outbox).appendCausedBy(
                eq(AccountEvents.FUNDS_CAPTURED),
                eq(PayFlowTopics.ACCOUNT_EVENTS),
                eq(PAYMENT_ID.toString()),
                eq(NOW),
                any(),
                eq(event));
    }

    @Test
    void releaseReturnsReservedFundsAndAppendsCompensationFact() {
        var fixture = reservedFixture();
        when(inbox.recordIfNew(any())).thenReturn(true);
        when(store.findAccountForUpdate(ACCOUNT_ID)).thenReturn(Optional.of(fixture.account()));
        when(store.findReservationForUpdate(RESERVATION_ID))
                .thenReturn(Optional.of(fixture.reservation()));
        var event = releaseEvent();
        var handler = new HandleReleaseFundsRequestedHandler(
                inbox, store, outbox, new ReleaseFundsPolicy(), clock, transactions);

        assertThat(handler.handle(event)).isEqualTo(EventProcessingResult.PROCESSED);
        assertThat(fixture.account().availableBalance()).isEqualTo(Money.of("100.0000", "VND"));
        assertThat(fixture.account().reservedBalance()).isEqualTo(Money.zero("VND"));
        assertThat(fixture.reservation().status()).isEqualTo(ReservationStatus.RELEASED);
        verify(outbox).appendCausedBy(
                eq(AccountEvents.FUNDS_RELEASED),
                eq(PayFlowTopics.ACCOUNT_EVENTS),
                eq(PAYMENT_ID.toString()),
                eq(NOW),
                any(),
                eq(event));
    }

    private static Fixture reservedFixture() {
        Account account = Account.open(ACCOUNT_ID, Money.of("100.0000", "VND"));
        Reservation reservation = account.reserve(
                RESERVATION_ID,
                PAYMENT_ID,
                Money.of("40.0000", "VND"),
                NOW.minusSeconds(10),
                NOW.plusSeconds(60));
        return new Fixture(account, reservation);
    }

    private static EventEnvelope<AccountReserveRequestedData> reserveEvent() {
        return EventEnvelope.of(
                UUID.randomUUID(),
                AccountEvents.RESERVE_REQUESTED,
                PAYMENT_ID.toString(),
                "corr-account-reservation-test",
                "payment-service",
                NOW.minusSeconds(1),
                new AccountReserveRequestedData(
                        PAYMENT_ID,
                        ACCOUNT_ID,
                        new BigDecimal("40.0000"),
                        "VND",
                        NOW.plusSeconds(60)));
    }

    private static EventEnvelope<AccountCaptureRequestedData> captureEvent() {
        return EventEnvelope.of(
                UUID.randomUUID(),
                AccountEvents.CAPTURE_REQUESTED,
                PAYMENT_ID.toString(),
                "corr-account-reservation-test",
                "payment-service",
                NOW.minusSeconds(1),
                new AccountCaptureRequestedData(
                        PAYMENT_ID,
                        ACCOUNT_ID,
                        RESERVATION_ID,
                        new BigDecimal("40.0000"),
                        "VND"));
    }

    private static EventEnvelope<AccountReleaseRequestedData> releaseEvent() {
        return EventEnvelope.of(
                UUID.randomUUID(),
                AccountEvents.RELEASE_REQUESTED,
                PAYMENT_ID.toString(),
                "corr-account-reservation-test",
                "payment-service",
                NOW.minusSeconds(1),
                new AccountReleaseRequestedData(
                        PAYMENT_ID,
                        ACCOUNT_ID,
                        RESERVATION_ID,
                        new BigDecimal("40.0000"),
                        "VND",
                        "LEDGER_POSTING_FAILED"));
    }

    private record Fixture(Account account, Reservation reservation) {}
}
