package com.payflow.ledger.application.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.payflow.ledger.application.inbox.EventProcessingResult;
import com.payflow.ledger.application.port.OutboxAppender;
import com.payflow.ledger.application.port.ProcessedEventStore;
import com.payflow.ledger.application.payment.PaymentJournalFactory;
import com.payflow.ledger.application.port.LedgerAccountDirectory;
import com.payflow.ledger.application.port.LedgerAccountPair;
import com.payflow.ledger.application.port.PaymentJournalRecord;
import com.payflow.ledger.application.port.PaymentJournalStore;
import com.payflow.ledger.domain.model.EntryDirection;
import com.payflow.ledger.domain.model.Journal;
import com.payflow.ledger.support.TestTransactionManager;
import com.payflow.events.EventEnvelope;
import com.payflow.events.PayFlowTopics;
import com.payflow.events.ledger.LedgerEvents;
import com.payflow.events.ledger.LedgerPostPaymentRequestedData;
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

class HandlePostPaymentRequestedHandlerTest {

    private static final UUID PAYMENT_ID = UUID.fromString("20000000-0000-4000-8000-000000000001");
    private static final UUID CUSTOMER_ID = UUID.fromString("30000000-0000-4000-8000-000000000001");
    private static final UUID MERCHANT_ID = UUID.fromString("40000000-0000-4000-8000-000000000001");
    private static final Instant NOW = Instant.parse("2026-07-30T01:00:00Z");

    private final ProcessedEventStore inbox = mock(ProcessedEventStore.class);
    private final LedgerAccountDirectory accounts = mock(LedgerAccountDirectory.class);
    private final PaymentJournalStore journals = mock(PaymentJournalStore.class);
    private final OutboxAppender outbox = mock(OutboxAppender.class);
    private HandlePostPaymentRequestedHandler handler;

    @BeforeEach
    void setUp() {
        handler = new HandlePostPaymentRequestedHandler(
                inbox,
                accounts,
                journals,
                outbox,
                new PaymentJournalFactory(),
                Clock.fixed(NOW, ZoneOffset.UTC),
                new TransactionTemplate(new TestTransactionManager()));
    }

    @Test
    void postsBalancedPaymentJournalAndAcknowledgement() {
        UUID merchantLedger = UUID.randomUUID();
        UUID customerLedger = UUID.randomUUID();
        when(inbox.recordIfNew(any())).thenReturn(true);
        when(journals.findByPaymentId(PAYMENT_ID)).thenReturn(Optional.empty());
        when(accounts.findPaymentAccounts(CUSTOMER_ID, MERCHANT_ID, "VND"))
                .thenReturn(Optional.of(new LedgerAccountPair(merchantLedger, customerLedger)));
        var event = event();

        assertThat(handler.handle(event)).isEqualTo(EventProcessingResult.PROCESSED);

        ArgumentCaptor<Journal> journal = ArgumentCaptor.forClass(Journal.class);
        verify(journals).save(journal.capture(), eq(event.data()));
        assertThat(journal.getValue().entries()).hasSize(2);
        assertThat(journal.getValue().entries().get(0).direction())
                .isEqualTo(EntryDirection.DEBIT);
        assertThat(journal.getValue().entries().get(0).ledgerAccountId())
                .isEqualTo(customerLedger);
        assertThat(journal.getValue().entries().get(1).ledgerAccountId())
                .isEqualTo(merchantLedger);
        verify(outbox).appendCausedBy(
                eq(LedgerEvents.PAYMENT_POSTED),
                eq(PayFlowTopics.LEDGER_EVENTS),
                eq(PAYMENT_ID.toString()),
                eq(NOW),
                any(),
                eq(event));
    }

    @Test
    void missingLedgerMappingProducesDefinitivePreJournalFailure() {
        when(inbox.recordIfNew(any())).thenReturn(true);
        when(journals.findByPaymentId(PAYMENT_ID)).thenReturn(Optional.empty());
        when(accounts.findPaymentAccounts(CUSTOMER_ID, MERCHANT_ID, "VND"))
                .thenReturn(Optional.empty());
        var event = event();

        assertThat(handler.handle(event)).isEqualTo(EventProcessingResult.PROCESSED);

        verify(journals, never()).save(any(), any());
        verify(outbox).appendCausedBy(
                eq(LedgerEvents.PAYMENT_POSTING_FAILED),
                eq(PayFlowTopics.LEDGER_EVENTS),
                eq(PAYMENT_ID.toString()),
                eq(NOW),
                any(),
                eq(event));
    }

    @Test
    void matchingBusinessDuplicateDoesNotPostAgain() {
        when(inbox.recordIfNew(any())).thenReturn(true);
        when(journals.findByPaymentId(PAYMENT_ID)).thenReturn(Optional.of(
                new PaymentJournalRecord(
                        PAYMENT_ID,
                        CUSTOMER_ID,
                        MERCHANT_ID,
                        UUID.randomUUID(),
                        new BigDecimal("40.0000"),
                        "VND")));

        assertThat(handler.handle(event())).isEqualTo(EventProcessingResult.BUSINESS_DUPLICATE);

        verify(journals, never()).save(any(), any());
        verify(outbox, never()).appendCausedBy(any(), any(), any(), any(), any(), any());
    }

    private static EventEnvelope<LedgerPostPaymentRequestedData> event() {
        return EventEnvelope.of(
                UUID.randomUUID(),
                LedgerEvents.POST_PAYMENT_REQUESTED,
                PAYMENT_ID.toString(),
                "corr-ledger-payment-test",
                "payment-service",
                NOW.minusSeconds(1),
                new LedgerPostPaymentRequestedData(
                        PAYMENT_ID,
                        CUSTOMER_ID,
                        MERCHANT_ID,
                        new BigDecimal("40.0000"),
                        "VND"));
    }
}
