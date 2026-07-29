package com.payflow.payment.application.handler;

import static org.assertj.core.api.Assertions.assertThat;

import com.payflow.events.EventType;
import com.payflow.events.account.AccountEvents;
import com.payflow.events.account.AccountReleaseRequestedData;
import com.payflow.events.ledger.LedgerEvents;
import com.payflow.events.payment.PaymentEvents;
import com.payflow.payment.application.exception.ConcurrentSagaUpdateException;
import com.payflow.payment.application.port.OutboxAppender;
import com.payflow.payment.application.port.PaymentSagaStore;
import com.payflow.payment.application.port.PaymentWorkflowStore;
import com.payflow.payment.application.saga.PaymentSagaRecoveryPolicy;
import com.payflow.payment.application.saga.SagaRecoveryBatchResult;
import com.payflow.payment.application.saga.SagaRecoverySettings;
import com.payflow.payment.application.saga.VersionedPayment;
import com.payflow.payment.application.saga.VersionedPaymentSaga;
import com.payflow.payment.domain.model.Money;
import com.payflow.payment.domain.model.Payment;
import com.payflow.payment.domain.model.PaymentIntake;
import com.payflow.payment.domain.model.PaymentSaga;
import com.payflow.payment.domain.model.PaymentSagaStatus;
import com.payflow.payment.domain.model.PaymentStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

class RecoverOverdueSagasHandlerTest {

    private static final Instant NOW = Instant.parse("2026-07-29T01:00:00Z");
    private static final Instant CREATED = NOW.minusSeconds(120);
    private static final UUID PAYMENT_ID =
            UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
    private static final UUID SAGA_ID =
            UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
    private static final UUID RESERVATION_ID =
            UUID.fromString("cccccccc-cccc-4ccc-8ccc-cccccccccccc");
    private static final UUID JOURNAL_ID =
            UUID.fromString("dddddddd-dddd-4ddd-8ddd-dddddddddddd");

    private final FakeSagaStore sagas = new FakeSagaStore();
    private final FakePaymentStore payments = new FakePaymentStore();
    private final RecordingOutbox outbox = new RecordingOutbox();
    private final RecordingTransactionManager transactionManager = new RecordingTransactionManager();

    @Test
    void emptyScanDoesNotOpenATransaction() {
        SagaRecoveryBatchResult result = handler(3).recoverDue();

        assertThat(result).isEqualTo(SagaRecoveryBatchResult.empty());
        assertThat(transactionManager.started).isZero();
    }

    @Test
    void overdueRiskStepPersistsRetryAndReappendsPaymentCreated() {
        add(riskSaga(), payment(PaymentStatus.RISK_CHECKING));

        SagaRecoveryBatchResult result = handler(1).recoverDue();

        assertThat(result.retried()).isEqualTo(1);
        assertThat(sagas.current().saga().retryCount()).isEqualTo(1);
        assertThat(outbox.appended).singleElement()
                .satisfies(event -> assertThat(event.type()).isEqualTo(PaymentEvents.PAYMENT_CREATED));
        assertThat(payments.updates).isZero();
        assertThat(transactionManager.committed).isEqualTo(1);
    }

    @Test
    void overdueReserveStepReappendsTheReserveCommand() {
        PaymentSaga saga = PaymentSaga.start(
                SAGA_ID, PAYMENT_ID, CREATED.plusSeconds(10), CREATED);
        saga.recordRiskApproved(NOW.minusSeconds(1), CREATED.plusSeconds(1));
        add(saga, payment(PaymentStatus.RESERVING_FUNDS));

        SagaRecoveryBatchResult result = handler(1).recoverDue();

        assertThat(result.retried()).isEqualTo(1);
        assertThat(outbox.appended).singleElement()
                .satisfies(event -> assertThat(event.type()).isEqualTo(AccountEvents.RESERVE_REQUESTED));
    }

    @Test
    void overdueLedgerStepReappendsTheLedgerCommand() {
        add(postLedgerSaga(), payment(PaymentStatus.PROCESSING));

        SagaRecoveryBatchResult result = handler(1).recoverDue();

        assertThat(result.retried()).isEqualTo(1);
        assertThat(outbox.appended).singleElement()
                .satisfies(event -> assertThat(event.type()).isEqualTo(LedgerEvents.POST_PAYMENT_REQUESTED));
    }

    @Test
    void overdueCaptureStepReappendsTheCaptureCommandWithoutReleasing() {
        PaymentSaga saga = postLedgerSaga();
        saga.recordLedgerPosted(JOURNAL_ID, NOW.minusSeconds(1), CREATED.plusSeconds(3));
        add(saga, payment(PaymentStatus.PROCESSING));

        SagaRecoveryBatchResult result = handler(1).recoverDue();

        assertThat(result.retried()).isEqualTo(1);
        assertThat(outbox.appended).singleElement()
                .satisfies(event -> assertThat(event.type()).isEqualTo(AccountEvents.CAPTURE_REQUESTED));
        assertThat(sagas.current().saga().journalId()).isEqualTo(JOURNAL_ID);
    }

    @Test
    void overdueCompensationReappendsReleaseWithTheNewPersistedIntent() {
        PaymentSaga saga = postLedgerSaga();
        saga.beginCompensation(
                "LEDGER_FAILED", NOW.minusSeconds(1), CREATED.plusSeconds(3));
        add(saga, payment(PaymentStatus.PROCESSING));

        SagaRecoveryBatchResult result = handler(1).recoverDue();

        assertThat(result.retried()).isEqualTo(1);
        assertThat(outbox.appended).singleElement().satisfies(event -> {
            assertThat(event.type()).isEqualTo(AccountEvents.RELEASE_REQUESTED);
            assertThat(((AccountReleaseRequestedData) event.data()).reasonCode())
                    .isEqualTo("SAGA_RELEASE_FUNDS_TIMEOUT");
        });
    }

    @Test
    void exhaustedPreLedgerStepStartsReleaseCompensation() {
        add(postLedgerSaga(), payment(PaymentStatus.PROCESSING));

        SagaRecoveryBatchResult result = handler(0).recoverDue();

        assertThat(result.compensating()).isEqualTo(1);
        assertThat(sagas.current().saga().status()).isEqualTo(PaymentSagaStatus.COMPENSATING);
        assertThat(outbox.appended).singleElement().satisfies(event -> {
            assertThat(event.type()).isEqualTo(AccountEvents.RELEASE_REQUESTED);
            assertThat(event.data()).isInstanceOf(AccountReleaseRequestedData.class);
        });
        assertThat(payments.updates).isZero();
    }

    @Test
    void exhaustedPostLedgerCaptureEntersManualReviewAndUpdatesBothAggregates() {
        PaymentSaga saga = postLedgerSaga();
        saga.recordLedgerPosted(JOURNAL_ID, NOW.minusSeconds(1), CREATED.plusSeconds(3));
        add(saga, payment(PaymentStatus.PROCESSING));

        SagaRecoveryBatchResult result = handler(0).recoverDue();

        assertThat(result.manualReview()).isEqualTo(1);
        assertThat(sagas.current().saga().status())
                .isEqualTo(PaymentSagaStatus.MANUAL_REVIEW_REQUIRED);
        assertThat(payments.current().payment().status())
                .isEqualTo(PaymentStatus.MANUAL_REVIEW_REQUIRED);
        assertThat(payments.updates).isEqualTo(1);
        assertThat(outbox.appended).singleElement()
                .satisfies(event ->
                        assertThat(event.type()).isEqualTo(PaymentEvents.MANUAL_REVIEW_REQUIRED));
    }

    @Test
    void optimisticRaceRollsBackTheLosingWorkerBeforeItAppendsAnEvent() {
        add(postLedgerSaga(), payment(PaymentStatus.PROCESSING));
        sagas.failNextUpdate = true;

        SagaRecoveryBatchResult result = handler(0).recoverDue();

        assertThat(result.concurrentUpdates()).isEqualTo(1);
        assertThat(outbox.appended).isEmpty();
        assertThat(transactionManager.rolledBack).isEqualTo(1);
        assertThat(transactionManager.committed).isZero();
    }

    @Test
    void aCandidateThatIsNoLongerDueCreatesNoWriteOrEvent() {
        PaymentSaga saga = PaymentSaga.start(
                SAGA_ID, PAYMENT_ID, NOW.plusSeconds(10), CREATED);
        add(saga, payment(PaymentStatus.RISK_CHECKING));

        SagaRecoveryBatchResult result = handler(3).recoverDue();

        assertThat(result.noAction()).isEqualTo(1);
        assertThat(sagas.updates).isZero();
        assertThat(outbox.appended).isEmpty();
    }

    private RecoverOverdueSagasHandler handler(int maxRetries) {
        return new RecoverOverdueSagasHandler(
                sagas,
                payments,
                new PaymentSagaRecoveryPolicy(),
                outbox,
                new SagaRecoverySettings(Duration.ofSeconds(30), maxRetries, 50),
                Clock.fixed(NOW, ZoneOffset.UTC),
                new TransactionTemplate(transactionManager));
    }

    private void add(PaymentSaga saga, Payment payment) {
        sagas.rows.put(saga.id(), new VersionedPaymentSaga(saga, 0));
        sagas.dueIds.add(saga.id());
        payments.rows.put(payment.id(), new VersionedPayment(payment, 0));
    }

    private static PaymentSaga riskSaga() {
        return PaymentSaga.start(SAGA_ID, PAYMENT_ID, NOW.minusSeconds(1), CREATED);
    }

    private static PaymentSaga postLedgerSaga() {
        PaymentSaga saga = PaymentSaga.start(
                SAGA_ID, PAYMENT_ID, CREATED.plusSeconds(10), CREATED);
        saga.recordRiskApproved(CREATED.plusSeconds(20), CREATED.plusSeconds(1));
        saga.recordFundsReserved(RESERVATION_ID, NOW.minusSeconds(1), CREATED.plusSeconds(2));
        return saga;
    }

    private static Payment payment(PaymentStatus status) {
        return Payment.rehydrateLegacyNoFee(
                PAYMENT_ID,
                UUID.fromString("eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"),
                new PaymentIntake(
                        PAYMENT_ID,
                        UUID.fromString("ffffffff-ffff-4fff-8fff-ffffffffffff"),
                        UUID.fromString("11111111-1111-4111-8111-111111111111"),
                        "ORDER-RECOVERY-SCHEDULER",
                        "scheduler-idempotency-key",
                        Money.of("500000", "VND"),
                        null,
                        Map.of(),
                        CREATED),
                status,
                CREATED.plusSeconds(3));
    }

    private static final class FakeSagaStore implements PaymentSagaStore {

        private final Map<UUID, VersionedPaymentSaga> rows = new LinkedHashMap<>();
        private final List<UUID> dueIds = new ArrayList<>();
        private int updates;
        private boolean failNextUpdate;

        @Override
        public void add(PaymentSaga saga) {
            rows.put(saga.id(), new VersionedPaymentSaga(saga, 0));
        }

        @Override
        public Optional<VersionedPaymentSaga> find(UUID sagaId) {
            return Optional.ofNullable(rows.get(sagaId));
        }

        @Override
        public Optional<VersionedPaymentSaga> findByPaymentId(UUID paymentId) {
            return rows.values().stream()
                    .filter(row -> row.saga().paymentId().equals(paymentId))
                    .findFirst();
        }

        @Override
        public List<UUID> findDueIds(Instant dueAt, int limit) {
            return dueIds.stream().limit(limit).toList();
        }

        @Override
        public void update(VersionedPaymentSaga stored) {
            if (failNextUpdate) {
                failNextUpdate = false;
                throw new ConcurrentSagaUpdateException(
                        "PaymentSaga", stored.saga().id(), stored.version());
            }
            updates++;
            rows.put(
                    stored.saga().id(),
                    new VersionedPaymentSaga(stored.saga(), stored.version() + 1));
        }

        VersionedPaymentSaga current() {
            return rows.get(SAGA_ID);
        }
    }

    private static final class FakePaymentStore implements PaymentWorkflowStore {

        private final Map<UUID, VersionedPayment> rows = new LinkedHashMap<>();
        private int updates;

        @Override
        public Optional<VersionedPayment> findForWorkflow(UUID paymentId) {
            return Optional.ofNullable(rows.get(paymentId));
        }

        @Override
        public void updateWorkflow(VersionedPayment stored) {
            updates++;
            rows.put(
                    stored.payment().id(),
                    new VersionedPayment(stored.payment(), stored.version() + 1));
        }

        VersionedPayment current() {
            return rows.get(PAYMENT_ID);
        }
    }

    private static final class RecordingOutbox implements OutboxAppender {

        private final List<Appended> appended = new ArrayList<>();

        @Override
        public <T> UUID append(
                EventType type, String topic, String aggregateId, Instant occurredAt, T data) {
            appended.add(new Appended(type, topic, aggregateId, occurredAt, data));
            return UUID.randomUUID();
        }

        @Override
        public <T> UUID appendCausedBy(
                EventType type,
                String topic,
                String aggregateId,
                Instant occurredAt,
                T data,
                com.payflow.events.EventEnvelope<?> cause) {
            return append(type, topic, aggregateId, occurredAt, data);
        }

        private record Appended(
                EventType type, String topic, String aggregateId, Instant occurredAt, Object data) {}
    }

    private static final class RecordingTransactionManager implements PlatformTransactionManager {

        private int started;
        private int committed;
        private int rolledBack;

        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            started++;
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
            committed++;
        }

        @Override
        public void rollback(TransactionStatus status) {
            rolledBack++;
        }
    }
}
