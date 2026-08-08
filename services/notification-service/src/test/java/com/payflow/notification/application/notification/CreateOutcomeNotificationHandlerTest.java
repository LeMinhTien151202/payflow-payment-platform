package com.payflow.notification.application.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.payflow.notification.application.inbox.EventProcessingResult;
import com.payflow.notification.application.port.NotificationRecord;
import com.payflow.notification.application.port.NotificationStore;
import com.payflow.notification.application.port.ProcessedEventStore;
import com.payflow.notification.application.port.WebhookDeliveryStore;
import com.payflow.notification.domain.exception.NotificationInvariantViolationException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

class CreateOutcomeNotificationHandlerTest {

    private static final Instant NOW = Instant.parse("2026-07-30T02:00:00Z");
    private final ProcessedEventStore inbox = mock(ProcessedEventStore.class);
    private final NotificationStore notifications = mock(NotificationStore.class);
    private final TransactionTemplate transactions = mock(TransactionTemplate.class);
    private final WebhookDeliveryStore webhooks = mock(WebhookDeliveryStore.class);
    private CreateOutcomeNotificationHandler handler;

    @BeforeEach
    @SuppressWarnings({"unchecked", "rawtypes"})
    void setUp() {
        when(transactions.execute(any(TransactionCallback.class))).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });
        handler = new CreateOutcomeNotificationHandler(inbox, notifications, webhooks, transactions,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void persistsOneNotificationForFirstDelivery() {
        var intent = intent("PAYMENT_SUCCEEDED");
        when(inbox.recordIfNew(any())).thenReturn(true);
        when(notifications.findByBusinessReference("PAYMENT_OUTCOME", intent.businessReferenceId()))
                .thenReturn(Optional.empty());
        when(notifications.saveIfAbsent(any())).thenReturn(true);

        assertThat(handler.handle(intent)).isEqualTo(EventProcessingResult.PROCESSED);
        verify(notifications).saveIfAbsent(any());
    }

    @Test
    void transportDuplicateDoesNotWriteNotification() {
        when(inbox.recordIfNew(any())).thenReturn(false);
        assertThat(handler.handle(intent("PAYMENT_SUCCEEDED")))
                .isEqualTo(EventProcessingResult.DUPLICATE);
        verify(notifications, never()).saveIfAbsent(any());
    }

    @Test
    void newEventIdForSameIntentIsBusinessDuplicate() {
        var incoming = intent("PAYMENT_SUCCEEDED");
        var stored = new OutcomeNotificationIntent(UUID.randomUUID(), incoming.sourceEventType(),
                incoming.aggregateId(), incoming.businessReferenceType(),
                incoming.businessReferenceId(), incoming.recipientType(), incoming.recipientId(),
                incoming.templateCode(), incoming.payload(), incoming.createdAt());
        when(inbox.recordIfNew(any())).thenReturn(true);
        when(notifications.findByBusinessReference(
                incoming.businessReferenceType(), incoming.businessReferenceId()))
                .thenReturn(Optional.of(new NotificationRecord(UUID.randomUUID(), stored)));

        assertThat(handler.handle(incoming)).isEqualTo(EventProcessingResult.BUSINESS_DUPLICATE);
        verify(notifications, never()).saveIfAbsent(any());
    }

    @Test
    void conflictingTerminalOutcomeFailsInsteadOfOverwriting() {
        var incoming = intent("PAYMENT_FAILED");
        var storedIntent = intent("PAYMENT_SUCCEEDED");
        when(inbox.recordIfNew(any())).thenReturn(true);
        when(notifications.findByBusinessReference(
                incoming.businessReferenceType(), incoming.businessReferenceId()))
                .thenReturn(Optional.of(new NotificationRecord(UUID.randomUUID(), storedIntent)));

        assertThatThrownBy(() -> handler.handle(incoming))
                .isInstanceOf(NotificationInvariantViolationException.class)
                .hasMessageContaining("different outcome");
    }

    @Test
    void losingConcurrentInsertRaceRechecksIntent() {
        var intent = intent("PAYMENT_SUCCEEDED");
        when(inbox.recordIfNew(any())).thenReturn(true);
        when(notifications.findByBusinessReference(
                intent.businessReferenceType(), intent.businessReferenceId()))
                .thenReturn(Optional.empty(),
                        Optional.of(new NotificationRecord(UUID.randomUUID(), intent)));
        when(notifications.saveIfAbsent(any())).thenReturn(false);

        assertThat(handler.handle(intent)).isEqualTo(EventProcessingResult.BUSINESS_DUPLICATE);
    }

    private static OutcomeNotificationIntent intent(String template) {
        UUID paymentId = UUID.fromString("10000000-0000-4000-8000-000000000001");
        return new OutcomeNotificationIntent(UUID.randomUUID(), template.toLowerCase().replace('_', '.'),
                paymentId.toString(), "PAYMENT_OUTCOME", paymentId, "CUSTOMER",
                UUID.fromString("20000000-0000-4000-8000-000000000001").toString(),
                template, Map.of("paymentId", paymentId.toString()), NOW);
    }
}
