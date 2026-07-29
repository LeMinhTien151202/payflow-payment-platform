package com.payflow.payment.application.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.payflow.events.EventEnvelope;
import com.payflow.events.EventType;
import com.payflow.events.PayFlowTopics;
import com.payflow.events.refund.RefundEvents;
import com.payflow.events.refund.RefundRequestedData;
import com.payflow.payment.application.CreateRefundResult;
import com.payflow.payment.application.RefundAcceptance;
import com.payflow.payment.application.command.CreateRefundCommand;
import com.payflow.payment.application.exception.IdempotencyConflictException;
import com.payflow.payment.application.idempotency.IdempotencyScope;
import com.payflow.payment.application.idempotency.RefundIdempotentResponse;
import com.payflow.payment.application.idempotency.RequestFingerprint;
import com.payflow.payment.application.port.IdGenerator;
import com.payflow.payment.application.port.OutboxAppender;
import com.payflow.payment.application.port.RefundIdempotencyStore;
import com.payflow.payment.application.port.RefundPaymentStore;
import com.payflow.payment.application.port.RefundRepository;
import com.payflow.payment.domain.exception.RefundCapacityExceededException;
import com.payflow.payment.domain.model.FeePolicySnapshot;
import com.payflow.payment.domain.model.Money;
import com.payflow.payment.domain.model.Payment;
import com.payflow.payment.domain.model.PaymentFeeSnapshot;
import com.payflow.payment.domain.model.PaymentIntake;
import com.payflow.payment.domain.model.PaymentStatus;
import com.payflow.payment.domain.model.Refund;
import com.payflow.payment.domain.model.RefundStatus;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

class CreateRefundHandlerTest {

    private static final UUID PAYMENT_ID = UUID.fromString("c73e17b5-aaca-48da-9ed5-bb0937499f01");
    private static final UUID REFUND_ID = UUID.fromString("73817fe8-219a-4136-921c-2473c1ea9e9b");
    private static final UUID MERCHANT_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final Instant NOW = Instant.parse("2026-07-29T09:00:00Z");
    private static final String KEY = "refund-key-1";

    private Payment payment;
    private FakePaymentStore payments;
    private FakeRefundRepository refunds;
    private FakeRefundIdempotencyStore idempotency;
    private FakeOutbox outbox;
    private CountingIds ids;
    private RecordingTransactions transactionManager;
    private CreateRefundHandler handler;

    @BeforeEach
    void setUp() {
        payment = succeededPayment();
        payments = new FakePaymentStore(payment);
        refunds = new FakeRefundRepository();
        idempotency = new FakeRefundIdempotencyStore();
        outbox = new FakeOutbox();
        ids = new CountingIds();
        transactionManager = new RecordingTransactions();
        handler = new CreateRefundHandler(
                payments,
                refunds,
                idempotency,
                outbox,
                ids,
                Clock.fixed(NOW, ZoneOffset.UTC),
                new TransactionTemplate(transactionManager));
    }

    @Test
    void acceptedRefundReservesCapacityAndWritesOneLogicalEvent() {
        CreateRefundResult result = handler.handle(command("60", "Khách trả hàng"));

        assertThat(result).isInstanceOf(CreateRefundResult.Accepted.class);
        assertThat(result.responseStatus()).isEqualTo(202);
        assertThat(result.refund().refundId()).isEqualTo(REFUND_ID);
        assertThat(result.refund().status()).isEqualTo(RefundStatus.CREATED);
        assertThat(payment.reservedRefundAmount()).isEqualTo(Money.of("60", "VND"));
        assertThat(payments.updates).isEqualTo(1);
        assertThat(refunds.saved).singleElement().satisfies(refund -> {
            assertThat(refund.paymentId()).isEqualTo(PAYMENT_ID);
            assertThat(refund.requestedBy()).isEqualTo("merchant-user-42");
            assertThat(refund.reason()).isEqualTo("Khách trả hàng");
        });

        assertThat(outbox.appended).singleElement().satisfies(event -> {
            assertThat(event.type()).isEqualTo(RefundEvents.REFUND_REQUESTED);
            assertThat(event.topic()).isEqualTo(PayFlowTopics.REFUND_EVENTS);
            assertThat(event.aggregateId()).isEqualTo(PAYMENT_ID.toString());
            assertThat(event.data()).isInstanceOf(RefundRequestedData.class);
            RefundRequestedData data = (RefundRequestedData) event.data();
            assertThat(data.refundId()).isEqualTo(REFUND_ID);
            assertThat(data.amount()).isEqualByComparingTo("60.0000");
        });
        assertThat(transactionManager.committed).isEqualTo(1);
    }

    @Test
    void identicalRetryReplaysWithoutLockingOrGeneratingAnotherId() {
        CreateRefundCommand command = command("60", "Khách trả hàng");
        RefundAcceptance acceptance = new RefundAcceptance(
                REFUND_ID, PAYMENT_ID, RefundStatus.CREATED, new BigDecimal("60.0000"), "VND", NOW);
        idempotency.stored = new RefundIdempotentResponse(
                RequestFingerprint.of(command), REFUND_ID, 202, acceptance);

        CreateRefundResult result = handler.handle(command);

        assertThat(result).isInstanceOf(CreateRefundResult.Replayed.class);
        assertThat(result.refund()).isEqualTo(acceptance);
        assertThat(payments.finds).isZero();
        assertThat(refunds.saved).isEmpty();
        assertThat(outbox.appended).isEmpty();
        assertThat(ids.calls).isZero();
        assertThat(transactionManager.started).isZero();
    }

    @Test
    void reusedKeyWithDifferentBodyIsConflictBeforeAnyWrite() {
        CreateRefundCommand original = command("60", "Khách trả hàng");
        idempotency.stored = new RefundIdempotentResponse(
                RequestFingerprint.of(original),
                REFUND_ID,
                202,
                new RefundAcceptance(
                        REFUND_ID,
                        PAYMENT_ID,
                        RefundStatus.CREATED,
                        new BigDecimal("60.0000"),
                        "VND",
                        NOW));

        assertThatThrownBy(() -> handler.handle(command("61", "Khách trả hàng")))
                .isInstanceOf(IdempotencyConflictException.class);
        assertThat(payments.finds).isZero();
        assertThat(refunds.saved).isEmpty();
        assertThat(outbox.appended).isEmpty();
    }

    @Test
    void excessiveRefundCreatesNoRefundIdempotencyOrOutbox() {
        assertThatThrownBy(() -> handler.handle(command("101", null)))
                .isInstanceOf(RefundCapacityExceededException.class);

        assertThat(payment.reservedRefundAmount()).isEqualTo(Money.zero("VND"));
        assertThat(refunds.saved).isEmpty();
        assertThat(idempotency.stored).isNull();
        assertThat(outbox.appended).isEmpty();
        assertThat(transactionManager.rolledBack).isEqualTo(1);
    }

    private static CreateRefundCommand command(String amount, String reason) {
        return new CreateRefundCommand(
                MERCHANT_ID,
                "merchant-user-42",
                PAYMENT_ID,
                KEY,
                new BigDecimal(amount),
                reason);
    }

    private static Payment succeededPayment() {
        PaymentIntake intake = new PaymentIntake(
                PAYMENT_ID,
                UUID.fromString("3beff442-7f10-4504-aab4-12d985cf3e95"),
                UUID.fromString("039bedb6-b2d6-47df-aa25-2035e39136a3"),
                "ORDER-REFUND-1",
                "payment-key-1",
                Money.of("100", "VND"),
                null,
                Map.of(),
                NOW.minusSeconds(60));
        PaymentFeeSnapshot fee = PaymentFeeSnapshot.calculate(
                new FeePolicySnapshot("FEE_V1", new BigDecimal("0.02"), RoundingMode.HALF_UP),
                intake.amount());
        return Payment.rehydrate(
                PAYMENT_ID,
                MERCHANT_ID,
                intake,
                fee,
                PaymentStatus.SUCCEEDED,
                Money.zero("VND"),
                Money.zero("VND"),
                Money.zero("VND"),
                NOW.minusSeconds(1));
    }

    private static final class FakePaymentStore implements RefundPaymentStore {
        private final Payment payment;
        private int finds;
        private int updates;

        private FakePaymentStore(Payment payment) {
            this.payment = payment;
        }

        @Override
        public Optional<Payment> findForRefund(UUID paymentId, UUID merchantId) {
            finds++;
            return payment.id().equals(paymentId) && payment.merchantId().equals(merchantId)
                    ? Optional.of(payment)
                    : Optional.empty();
        }

        @Override
        public void updateRefundState(Payment payment) {
            updates++;
        }
    }

    private static final class FakeRefundRepository implements RefundRepository {
        private final List<Refund> saved = new ArrayList<>();

        @Override
        public void save(Refund refund) {
            saved.add(refund);
        }
    }

    private static final class FakeRefundIdempotencyStore implements RefundIdempotencyStore {
        private RefundIdempotentResponse stored;

        @Override
        public Optional<RefundIdempotentResponse> find(String scope, String idempotencyKey) {
            return Optional.ofNullable(stored);
        }

        @Override
        public void record(
                String scope,
                String idempotencyKey,
                RefundIdempotentResponse response,
                Instant expiresAt) {
            assertThat(scope).isEqualTo(IdempotencyScope.createRefund(MERCHANT_ID));
            assertThat(expiresAt).isEqualTo(NOW.plus(CreateRefundHandler.REPLAY_WINDOW));
            stored = response;
        }
    }

    private static final class FakeOutbox implements OutboxAppender {
        private record Appended(EventType type, String topic, String aggregateId, Object data) {}

        private final List<Appended> appended = new ArrayList<>();

        @Override
        public <T> UUID append(
                EventType type, String topic, String aggregateId, Instant occurredAt, T data) {
            appended.add(new Appended(type, topic, aggregateId, data));
            return UUID.randomUUID();
        }

        @Override
        public <T> UUID appendCausedBy(
                EventType type,
                String topic,
                String aggregateId,
                Instant occurredAt,
                T data,
                EventEnvelope<?> cause) {
            return append(type, topic, aggregateId, occurredAt, data);
        }
    }

    private static final class CountingIds implements IdGenerator {
        private int calls;

        @Override
        public UUID newId() {
            calls++;
            return REFUND_ID;
        }
    }

    private static final class RecordingTransactions implements PlatformTransactionManager {
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
