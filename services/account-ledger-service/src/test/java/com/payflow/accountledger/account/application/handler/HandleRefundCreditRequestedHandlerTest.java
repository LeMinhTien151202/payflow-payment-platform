package com.payflow.accountledger.account.application.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.payflow.accountledger.account.application.port.AccountRefundStore;
import com.payflow.accountledger.account.application.refund.RefundCreditPolicy;
import com.payflow.accountledger.account.domain.model.Account;
import com.payflow.accountledger.account.domain.model.Money;
import com.payflow.accountledger.account.domain.model.RefundCredit;
import com.payflow.accountledger.application.inbox.EventProcessingResult;
import com.payflow.accountledger.application.port.OutboxAppender;
import com.payflow.accountledger.application.port.ProcessedEventStore;
import com.payflow.accountledger.support.TestTransactionManager;
import com.payflow.events.EventEnvelope;
import com.payflow.events.PayFlowTopics;
import com.payflow.events.account.AccountEvents;
import com.payflow.events.account.AccountRefundCreditRequestedData;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionTemplate;

class HandleRefundCreditRequestedHandlerTest {

    private static final UUID REFUND_ID = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID PAYMENT_ID = UUID.fromString("20000000-0000-4000-8000-000000000001");
    private static final UUID ACCOUNT_ID = UUID.fromString("40000000-0000-4000-8000-000000000001");
    private static final UUID JOURNAL_ID = UUID.fromString("50000000-0000-4000-8000-000000000001");
    private static final Instant NOW = Instant.parse("2026-07-29T13:01:00Z");

    private final ProcessedEventStore inbox = mock(ProcessedEventStore.class);
    private final AccountRefundStore store = mock(AccountRefundStore.class);
    private final OutboxAppender outbox = mock(OutboxAppender.class);
    private HandleRefundCreditRequestedHandler handler;

    @BeforeEach
    void setUp() {
        handler = new HandleRefundCreditRequestedHandler(
                inbox,
                store,
                outbox,
                new RefundCreditPolicy(),
                Clock.fixed(NOW, ZoneOffset.UTC),
                new TransactionTemplate(new TestTransactionManager()));
    }

    @Test
    void creditsAccountAndAppendsAcknowledgement() {
        Account account = Account.open(ACCOUNT_ID, Money.of("100", "VND"));
        when(inbox.recordIfNew(any())).thenReturn(true);
        when(store.findAccountForUpdate(ACCOUNT_ID)).thenReturn(Optional.of(account));
        when(store.findCreditByRefundId(REFUND_ID)).thenReturn(Optional.empty());
        EventEnvelope<AccountRefundCreditRequestedData> event = event();

        assertThat(handler.handle(event)).isEqualTo(EventProcessingResult.PROCESSED);
        assertThat(account.availableBalance()).isEqualTo(Money.of("140", "VND"));
        verify(store).updateAccount(account);
        ArgumentCaptor<RefundCredit> credit = ArgumentCaptor.forClass(RefundCredit.class);
        verify(store).saveCredit(credit.capture());
        assertThat(credit.getValue().journalId()).isEqualTo(JOURNAL_ID);
        verify(outbox).appendCausedBy(
                eq(AccountEvents.REFUND_CREDITED),
                eq(PayFlowTopics.ACCOUNT_EVENTS),
                eq(PAYMENT_ID.toString()),
                eq(NOW),
                any(),
                eq(event));
    }

    @Test
    void transportDuplicateStopsBeforeAccountLock() {
        when(inbox.recordIfNew(any())).thenReturn(false);

        assertThat(handler.handle(event())).isEqualTo(EventProcessingResult.DUPLICATE);

        verify(store, never()).findAccountForUpdate(any());
        verify(outbox, never()).appendCausedBy(any(), any(), any(), any(), any(), any());
    }

    @Test
    void matchingBusinessDuplicateDoesNotCreditOrAppendAgain() {
        Account account = Account.open(ACCOUNT_ID, Money.of("140", "VND"));
        when(inbox.recordIfNew(any())).thenReturn(true);
        when(store.findAccountForUpdate(ACCOUNT_ID)).thenReturn(Optional.of(account));
        when(store.findCreditByRefundId(REFUND_ID)).thenReturn(Optional.of(new RefundCredit(
                UUID.randomUUID(),
                REFUND_ID,
                PAYMENT_ID,
                ACCOUNT_ID,
                JOURNAL_ID,
                Money.of("40", "VND"),
                NOW.minusSeconds(1))));

        assertThat(handler.handle(event())).isEqualTo(EventProcessingResult.BUSINESS_DUPLICATE);
        assertThat(account.availableBalance()).isEqualTo(Money.of("140", "VND"));
        verify(store, never()).updateAccount(any());
        verify(store, never()).saveCredit(any());
        verify(outbox, never()).appendCausedBy(any(), any(), any(), any(), any(), any());
    }

    private static EventEnvelope<AccountRefundCreditRequestedData> event() {
        return EventEnvelope.of(
                UUID.randomUUID(),
                AccountEvents.REFUND_CREDIT_REQUESTED,
                PAYMENT_ID.toString(),
                "corr-account-refund-test",
                "payment-service",
                NOW.minusSeconds(1),
                new AccountRefundCreditRequestedData(
                        REFUND_ID,
                        PAYMENT_ID,
                        ACCOUNT_ID,
                        JOURNAL_ID,
                        new BigDecimal("40.0000"),
                        "VND"));
    }
}
