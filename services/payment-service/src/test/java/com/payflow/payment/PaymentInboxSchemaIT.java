package com.payflow.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.payflow.payment.application.inbox.IncomingEventIdentity;
import com.payflow.payment.application.port.ProcessedEventStore;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class PaymentInboxSchemaIT extends AbstractPostgresIT {

    @Autowired
    private ProcessedEventStore processedEventStore;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactions;

    @Test
    void duplicateIsANoOpAndDoesNotAbortTheSurroundingTransaction() {
        IncomingEventIdentity event = event();

        Boolean transactionCompleted = transactions.execute(status -> {
            assertThat(processedEventStore.recordIfNew(event)).isTrue();
            assertThat(processedEventStore.recordIfNew(event)).isFalse();
            assertThat(jdbc.queryForObject("select 40 + 2", Integer.class)).isEqualTo(42);
            return true;
        });

        assertThat(transactionCompleted).isTrue();
        assertThat(jdbc.queryForObject(
                        "select count(*) from payment.processed_events where event_id = ?",
                        Integer.class,
                        event.eventId()))
                .isEqualTo(1);
    }

    @Test
    void sameEventMayBeConsumedOnceByEachLogicalConsumer() {
        IncomingEventIdentity first = event();
        IncomingEventIdentity second = new IncomingEventIdentity(
                first.eventId(),
                "payment-notification-v1",
                first.eventType(),
                first.aggregateId(),
                first.processedAt());

        transactions.executeWithoutResult(status -> {
            assertThat(processedEventStore.recordIfNew(first)).isTrue();
            assertThat(processedEventStore.recordIfNew(second)).isTrue();
        });

        assertThat(jdbc.queryForObject(
                        "select count(*) from payment.processed_events where event_id = ?",
                        Integer.class,
                        first.eventId()))
                .isEqualTo(2);
    }

    @Test
    void adapterFailsFastOutsideAnApplicationTransaction() {
        assertThatThrownBy(() -> processedEventStore.recordIfNew(event()))
                .isInstanceOf(IllegalTransactionStateException.class)
                .hasMessageContaining("existing transaction");
    }

    @Test
    void twoConcurrentTransactionsHaveExactlyOneWinner() throws Exception {
        IncomingEventIdentity event = event();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<Boolean> first = executor.submit(() -> recordAfterBarrier(event, ready, start));
            Future<Boolean> second = executor.submit(() -> recordAfterBarrier(event, ready, start));

            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        }

        assertThat(count(event.eventId())).isEqualTo(1);
    }

    @Test
    void rollbackRemovesInboxClaimSoRedeliveryMayTryAgain() {
        IncomingEventIdentity event = event();

        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
                    assertThat(processedEventStore.recordIfNew(event)).isTrue();
                    throw new IllegalStateException("injected business failure");
                }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("injected business failure");
        assertThat(count(event.eventId())).isZero();

        assertThat(transactions.execute(status -> processedEventStore.recordIfNew(event))).isTrue();
        assertThat(count(event.eventId())).isEqualTo(1);
    }

    private boolean recordAfterBarrier(
            IncomingEventIdentity event, CountDownLatch ready, CountDownLatch start) {
        Boolean result = transactions.execute(status -> {
            ready.countDown();
            try {
                if (!start.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("concurrency barrier timed out");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("concurrency test interrupted", exception);
            }
            return processedEventStore.recordIfNew(event);
        });
        return Boolean.TRUE.equals(result);
    }

    private int count(UUID eventId) {
        Integer count = jdbc.queryForObject(
                "select count(*) from payment.processed_events where event_id = ?",
                Integer.class,
                eventId);
        return count == null ? 0 : count;
    }

    private static IncomingEventIdentity event() {
        UUID eventId = UUID.randomUUID();
        return new IncomingEventIdentity(
                eventId,
                "payment-risk-assessment-v1",
                "risk.assessment.completed",
                UUID.randomUUID().toString(),
                Instant.parse("2026-07-28T00:00:00Z"));
    }
}
