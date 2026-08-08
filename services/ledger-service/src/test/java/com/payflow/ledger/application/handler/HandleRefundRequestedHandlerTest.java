package com.payflow.ledger.application.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.payflow.ledger.application.exception.RefundCommandContractException;
import com.payflow.ledger.application.inbox.EventProcessingResult;
import com.payflow.ledger.application.port.OutboxAppender;
import com.payflow.ledger.application.port.ProcessedEventStore;
import com.payflow.ledger.application.port.LedgerAccountDirectory;
import com.payflow.ledger.application.port.LedgerAccountPair;
import com.payflow.ledger.application.port.RefundJournalRecord;
import com.payflow.ledger.application.port.RefundJournalStore;
import com.payflow.ledger.application.refund.RefundJournalFactory;
import com.payflow.ledger.domain.model.Journal;
import com.payflow.ledger.support.TestTransactionManager;
import com.payflow.events.EventEnvelope;
import com.payflow.events.PayFlowTopics;
import com.payflow.events.ledger.LedgerEvents;
import com.payflow.events.refund.RefundEvents;
import com.payflow.events.refund.RefundRequestedData;
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

class HandleRefundRequestedHandlerTest {

    private static final UUID REFUND_ID = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID PAYMENT_ID = UUID.fromString("20000000-0000-4000-8000-000000000001");
    private static final UUID MERCHANT_ID = UUID.fromString("30000000-0000-4000-8000-000000000001");
    private static final UUID ACCOUNT_ID = UUID.fromString("40000000-0000-4000-8000-000000000001");
    private static final Instant NOW = Instant.parse("2026-07-29T13:00:00Z");

    private final ProcessedEventStore inbox = mock(ProcessedEventStore.class);
    private final LedgerAccountDirectory accounts = mock(LedgerAccountDirectory.class);
    private final RefundJournalStore journals = mock(RefundJournalStore.class);
    private final OutboxAppender outbox = mock(OutboxAppender.class);
    private HandleRefundRequestedHandler handler;

    @BeforeEach
    void setUp() {
        handler = new HandleRefundRequestedHandler(
                inbox,
                accounts,
                journals,
                outbox,
                new RefundJournalFactory(),
                Clock.fixed(NOW, ZoneOffset.UTC),
                new TransactionTemplate(new TestTransactionManager()));
    }

    @Test
    void postsBalancedJournalAndCausalAcknowledgement() {
        when(inbox.recordIfNew(any())).thenReturn(true);
        when(journals.findByRefundId(REFUND_ID)).thenReturn(Optional.empty());
        when(accounts.findRefundAccounts(MERCHANT_ID, ACCOUNT_ID, "VND"))
                .thenReturn(Optional.of(new LedgerAccountPair(UUID.randomUUID(), UUID.randomUUID())));
        EventEnvelope<RefundRequestedData> event = event();

        assertThat(handler.handle(event)).isEqualTo(EventProcessingResult.PROCESSED);

        ArgumentCaptor<Journal> journal = ArgumentCaptor.forClass(Journal.class);
        verify(journals).save(journal.capture(), eq(event.data()));
        assertThat(journal.getValue().entries()).hasSize(2);
        assertThat(journal.getValue().referenceId()).isEqualTo(REFUND_ID);
        verify(outbox).appendCausedBy(
                eq(LedgerEvents.REFUND_POSTED),
                eq(PayFlowTopics.LEDGER_EVENTS),
                eq(PAYMENT_ID.toString()),
                eq(NOW),
                any(),
                eq(event));
    }

    @Test
    void transportDuplicateStopsBeforeBusinessReads() {
        when(inbox.recordIfNew(any())).thenReturn(false);

        assertThat(handler.handle(event())).isEqualTo(EventProcessingResult.DUPLICATE);

        verify(journals, never()).findByRefundId(any());
        verify(outbox, never()).appendCausedBy(any(), any(), any(), any(), any(), any());
    }

    @Test
    void matchingBusinessDuplicateDoesNotCreateAnotherJournalOrEvent() {
        when(inbox.recordIfNew(any())).thenReturn(true);
        when(journals.findByRefundId(REFUND_ID)).thenReturn(Optional.of(existing("40.0000")));

        assertThat(handler.handle(event())).isEqualTo(EventProcessingResult.BUSINESS_DUPLICATE);

        verify(journals, never()).save(any(), any());
        verify(outbox, never()).appendCausedBy(any(), any(), any(), any(), any(), any());
    }

    @Test
    void conflictingBusinessDuplicateIsRejected() {
        when(inbox.recordIfNew(any())).thenReturn(true);
        when(journals.findByRefundId(REFUND_ID)).thenReturn(Optional.of(existing("39.0000")));

        assertThatThrownBy(() -> handler.handle(event()))
                .isInstanceOf(RefundCommandContractException.class)
                .hasMessageContaining("duplicate intent");
        verify(outbox, never()).appendCausedBy(any(), any(), any(), any(), any(), any());
    }

    private static RefundJournalRecord existing(String amount) {
        return new RefundJournalRecord(
                REFUND_ID,
                PAYMENT_ID,
                MERCHANT_ID,
                ACCOUNT_ID,
                UUID.randomUUID(),
                new BigDecimal(amount),
                "VND");
    }

    private static EventEnvelope<RefundRequestedData> event() {
        return EventEnvelope.of(
                UUID.randomUUID(),
                RefundEvents.REFUND_REQUESTED,
                PAYMENT_ID.toString(),
                "corr-ledger-refund-test",
                "payment-service",
                NOW.minusSeconds(1),
                new RefundRequestedData(
                        REFUND_ID,
                        PAYMENT_ID,
                        MERCHANT_ID,
                        UUID.randomUUID(),
                        ACCOUNT_ID,
                        new BigDecimal("40.0000"),
                        "VND",
                        NOW.minusSeconds(1)));
    }
}
