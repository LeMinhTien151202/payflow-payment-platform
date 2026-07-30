package com.payflow.risk.application.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.payflow.events.EventEnvelope;
import com.payflow.events.PayFlowTopics;
import com.payflow.events.payment.PaymentCreatedData;
import com.payflow.events.payment.PaymentEvents;
import com.payflow.events.risk.RiskAssessmentCompletedData;
import com.payflow.risk.application.event.RiskAssessmentEventFactory;
import com.payflow.risk.application.inbox.EventProcessingResult;
import com.payflow.risk.application.port.OutboxAppender;
import com.payflow.risk.application.port.ProcessedEventStore;
import com.payflow.risk.application.port.RiskAssessmentRecord;
import com.payflow.risk.application.port.RiskAssessmentStore;
import com.payflow.risk.application.port.RiskSignalProvider;
import com.payflow.risk.application.port.RiskSignalSnapshot;
import com.payflow.risk.domain.exception.RiskInvariantViolationException;
import com.payflow.risk.domain.model.RiskAssessment;
import com.payflow.risk.domain.model.RiskDecision;
import com.payflow.risk.domain.model.RiskLevel;
import com.payflow.risk.domain.policy.RiskRuleEngine;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

class HandlePaymentCreatedHandlerTest {

    private static final Instant NOW = Instant.parse("2026-07-30T02:00:00Z");
    private static final UUID PAYMENT_ID = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID MERCHANT_ID = UUID.fromString("20000000-0000-4000-8000-000000000001");
    private static final UUID CUSTOMER_ID = UUID.fromString("30000000-0000-4000-8000-000000000001");
    private static final UUID ACCOUNT_ID = UUID.fromString("40000000-0000-4000-8000-000000000001");

    private final RiskSignalProvider signals = mock(RiskSignalProvider.class);
    private final ProcessedEventStore inbox = mock(ProcessedEventStore.class);
    private final RiskAssessmentStore assessments = mock(RiskAssessmentStore.class);
    private final OutboxAppender outbox = mock(OutboxAppender.class);
    private final TransactionTemplate transactions = mock(TransactionTemplate.class);
    private HandlePaymentCreatedHandler handler;

    @BeforeEach
    @SuppressWarnings({"unchecked", "rawtypes"})
    void setUp() {
        when(transactions.execute(any(TransactionCallback.class))).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });
        handler = new HandlePaymentCreatedHandler(
                signals,
                inbox,
                assessments,
                outbox,
                new RiskRuleEngine(),
                new RiskAssessmentEventFactory(),
                transactions,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void persistsAssessmentAndCausalOutboxForFirstDelivery() {
        var event = paymentCreated(new BigDecimal("10000000.0000"));
        var snapshot = new RiskSignalSnapshot(6, new BigDecimal("31000000.0000"),
                false, 0, false, false);
        when(signals.collect(event.data())).thenReturn(snapshot);
        when(inbox.recordIfNew(any())).thenReturn(true);
        when(assessments.findByPaymentId(PAYMENT_ID)).thenReturn(Optional.empty());
        AtomicReference<RiskAssessmentRecord> saved = new AtomicReference<>();
        AtomicReference<EventEnvelope<?>> emitted = new AtomicReference<>();
        when(assessments.saveIfAbsent(any())).thenAnswer(call -> {
            saved.set(call.getArgument(0));
            return true;
        });
        org.mockito.Mockito.doAnswer(call -> {
                    emitted.set(call.getArgument(1));
                    return null;
                })
                .when(outbox)
                .append(org.mockito.ArgumentMatchers.eq(PayFlowTopics.RISK_EVENTS), any());

        assertThat(handler.handle(event)).isEqualTo(EventProcessingResult.PROCESSED);

        assertThat(saved.get().signals()).isEqualTo(snapshot);
        assertThat(saved.get().assessment().score()).isEqualTo(100);
        assertThat(saved.get().assessment().decision()).isEqualTo(RiskDecision.REJECTED);
        assertThat(emitted.get().eventType()).isEqualTo("risk.assessment.completed");
        assertThat(emitted.get().aggregateId()).isEqualTo(PAYMENT_ID.toString());
        assertThat(emitted.get().causationId()).isEqualTo(event.eventId().toString());
        assertThat(((RiskAssessmentCompletedData) emitted.get().data()).policyVersion())
                .isEqualTo("risk-v1");
    }

    @Test
    void transportDuplicateDoesNotMutateAssessmentOrOutbox() {
        var event = paymentCreated(new BigDecimal("100.0000"));
        when(signals.collect(event.data())).thenReturn(
                new RiskSignalSnapshot(1, event.data().amount(), false, 0, false, false));
        when(inbox.recordIfNew(any())).thenReturn(false);

        assertThat(handler.handle(event)).isEqualTo(EventProcessingResult.DUPLICATE);

        verify(assessments, never()).saveIfAbsent(any());
        verify(outbox, never()).append(any(), any());
    }

    @Test
    void sameBusinessIntentWithNewEventIdDoesNotEmitTwice() {
        var event = paymentCreated(new BigDecimal("100.0000"));
        var snapshot = new RiskSignalSnapshot(1, event.data().amount(), false, 0, false, false);
        when(signals.collect(event.data())).thenReturn(snapshot);
        when(inbox.recordIfNew(any())).thenReturn(true);
        when(assessments.findByPaymentId(PAYMENT_ID)).thenReturn(Optional.of(existing(event.data(), snapshot)));

        assertThat(handler.handle(event)).isEqualTo(EventProcessingResult.BUSINESS_DUPLICATE);
        verify(outbox, never()).append(any(), any());
    }

    @Test
    void concurrentInsertLosingUniqueRaceBecomesBusinessDuplicateWithoutSecondOutbox() {
        var event = paymentCreated(new BigDecimal("100.0000"));
        var snapshot = new RiskSignalSnapshot(1, event.data().amount(), false, 0, false, false);
        when(signals.collect(event.data())).thenReturn(snapshot);
        when(inbox.recordIfNew(any())).thenReturn(true);
        when(assessments.findByPaymentId(PAYMENT_ID))
                .thenReturn(Optional.empty(), Optional.of(existing(event.data(), snapshot)));
        when(assessments.saveIfAbsent(any())).thenReturn(false);

        assertThat(handler.handle(event)).isEqualTo(EventProcessingResult.BUSINESS_DUPLICATE);
        verify(outbox, never()).append(any(), any());
    }

    @Test
    void rejectsConflictingIntentForAlreadyAssessedPayment() {
        var event = paymentCreated(new BigDecimal("100.0000"));
        var snapshot = new RiskSignalSnapshot(1, event.data().amount(), false, 0, false, false);
        PaymentCreatedData stored = new PaymentCreatedData(
                PAYMENT_ID, MERCHANT_ID, CUSTOMER_ID, ACCOUNT_ID,
                new BigDecimal("99.0000"), "VND", NOW.minusSeconds(1));
        when(signals.collect(event.data())).thenReturn(snapshot);
        when(inbox.recordIfNew(any())).thenReturn(true);
        when(assessments.findByPaymentId(PAYMENT_ID)).thenReturn(Optional.of(existing(stored, snapshot)));

        assertThatThrownBy(() -> handler.handle(event))
                .isInstanceOf(RiskInvariantViolationException.class)
                .hasMessageContaining("different intent");
        verify(outbox, never()).append(any(), any());
    }

    @Test
    void invalidContractNeverTouchesRedisOrDatabase() {
        EventEnvelope<PaymentCreatedData> event = paymentCreated(new BigDecimal("100.0000"));
        var invalid = new EventEnvelope<>(
                event.eventId(), "payment.created", 2, event.aggregateType(), event.aggregateId(),
                event.correlationId(), null, event.producer(), event.occurredAt(), event.data());

        assertThatThrownBy(() -> handler.handle(invalid))
                .isInstanceOf(RiskInvariantViolationException.class);
        verify(signals, never()).collect(any());
        verify(inbox, never()).recordIfNew(any());
    }

    @Test
    void redisFailureStartsNoPostgresTransactionAndCannotSilentlyApprove() {
        var event = paymentCreated(new BigDecimal("100.0000"));
        when(signals.collect(event.data()))
                .thenThrow(new IllegalStateException("Redis unavailable"));

        assertThatThrownBy(() -> handler.handle(event))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Redis unavailable");
        verify(transactions, never()).execute(any(TransactionCallback.class));
        verify(inbox, never()).recordIfNew(any());
        verify(outbox, never()).append(any(), any());
    }

    private static RiskAssessmentRecord existing(
            PaymentCreatedData payment, RiskSignalSnapshot snapshot) {
        return new RiskAssessmentRecord(
                UUID.randomUUID(),
                payment,
                snapshot,
                new RiskAssessment(PAYMENT_ID, CUSTOMER_ID, MERCHANT_ID, "risk-v1", 0,
                        RiskLevel.LOW, RiskDecision.APPROVED, List.of()),
                NOW);
    }

    private static EventEnvelope<PaymentCreatedData> paymentCreated(BigDecimal amount) {
        var data = new PaymentCreatedData(
                PAYMENT_ID, MERCHANT_ID, CUSTOMER_ID, ACCOUNT_ID, amount, "VND", NOW.minusSeconds(1));
        return EventEnvelope.of(
                UUID.randomUUID(), PaymentEvents.PAYMENT_CREATED, PAYMENT_ID.toString(),
                "risk-handler-test", "payment-service", data.createdAt(), data);
    }
}
