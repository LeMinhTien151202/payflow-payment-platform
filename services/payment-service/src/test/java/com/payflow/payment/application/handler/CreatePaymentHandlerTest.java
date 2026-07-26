package com.payflow.payment.application.handler;

import static com.payflow.payment.application.CreatePaymentCommands.CUSTOMER_ID;
import static com.payflow.payment.application.CreatePaymentCommands.IDEMPOTENCY_KEY;
import static com.payflow.payment.application.CreatePaymentCommands.MERCHANT_ID;
import static com.payflow.payment.application.CreatePaymentCommands.MERCHANT_REFERENCE;
import static com.payflow.payment.application.CreatePaymentCommands.OTHER_MERCHANT_ID;
import static com.payflow.payment.application.CreatePaymentCommands.SOURCE_ACCOUNT_ID;
import static com.payflow.payment.application.CreatePaymentCommands.request;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.payflow.events.EventType;
import com.payflow.events.PayFlowTopics;
import com.payflow.events.payment.PaymentCreatedData;
import com.payflow.events.payment.PaymentEvents;
import com.payflow.payment.application.CreatePaymentResult;
import com.payflow.payment.application.PaymentAcceptance;
import com.payflow.payment.application.command.CreatePaymentCommand;
import com.payflow.payment.application.exception.ConcurrentIdempotentRequestException;
import com.payflow.payment.application.exception.DuplicateMerchantReferenceException;
import com.payflow.payment.application.exception.IdempotencyConflictException;
import com.payflow.payment.application.exception.MerchantNotRegisteredException;
import com.payflow.payment.application.idempotency.IdempotencyScope;
import com.payflow.payment.application.idempotency.IdempotentResponse;
import com.payflow.payment.application.idempotency.RequestFingerprint;
import com.payflow.payment.application.port.IdGenerator;
import com.payflow.payment.application.port.IdempotencyStore;
import com.payflow.payment.application.port.MerchantCatalog;
import com.payflow.payment.application.port.OutboxAppender;
import com.payflow.payment.application.port.PaymentRepository;
import com.payflow.payment.domain.exception.MerchantNotAcceptingPaymentsException;
import com.payflow.payment.domain.exception.PaymentLimitExceededException;
import com.payflow.payment.domain.exception.UnsupportedCurrencyException;
import com.payflow.payment.domain.model.MerchantSnapshot;
import com.payflow.payment.domain.model.MerchantStatus;
import com.payflow.payment.domain.model.Money;
import com.payflow.payment.domain.model.Payment;
import com.payflow.payment.domain.model.PaymentStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Tests the create-payment use case against in-memory ports.
 *
 * <h2>What these tests can and cannot prove</h2>
 *
 * <p>They prove the decisions the use case makes: which port is called, in what order, what a reused key
 * produces, and what happens when a concurrent request wins the race. Every one of those is logic in
 * {@link CreatePaymentHandler}, and a fake is the honest way to observe it.
 *
 * <p>They prove nothing about atomicity. The transaction manager here counts commits and rollbacks; it does
 * not undo anything, because the fakes are maps. That the payment and its outbox row land together or not at
 * all is a property of PostgreSQL and the real adapters, and AGENTS.md section 11 is explicit that it must be
 * shown with Testcontainers rather than asserted here. The same applies to the two unique indexes: what these
 * tests fix is what the use case does <em>once</em> a violation is reported, not that the database reports it.
 */
class CreatePaymentHandlerTest {

    private static final Instant NOW = Instant.parse("2026-07-26T09:15:00Z");
    private static final UUID PAYMENT_ID = UUID.fromString("c73e17b5-aaca-48da-9ed5-bb0937499f01");
    private static final UUID WINNER_PAYMENT_ID = UUID.fromString("8f14e45f-ceea-467a-9cf1-9ba9d1e6b3f2");
    private static final String SCOPE = IdempotencyScope.createPayment(MERCHANT_ID);

    private final List<String> journal = new ArrayList<>();
    private final Map<UUID, MerchantSnapshot> catalog = new HashMap<>();
    private final MerchantCatalog merchants = id -> Optional.ofNullable(catalog.get(id));
    private final FakePaymentRepository payments = new FakePaymentRepository(journal);
    private final FakeIdempotencyStore idempotency = new FakeIdempotencyStore(journal);
    private final FakeOutboxAppender outbox = new FakeOutboxAppender(journal);
    private final CountingIdGenerator ids = new CountingIdGenerator(PAYMENT_ID);
    private final RecordingTransactionManager transactionManager = new RecordingTransactionManager();

    private final CreatePaymentHandler handler =
            new CreatePaymentHandler(
                    merchants,
                    payments,
                    idempotency,
                    outbox,
                    ids,
                    Clock.fixed(NOW, ZoneOffset.UTC),
                    new TransactionTemplate(transactionManager));

    CreatePaymentHandlerTest() {
        catalog.put(MERCHANT_ID, merchant(MERCHANT_ID, MerchantStatus.ACTIVE, "50000000"));
        catalog.put(OTHER_MERCHANT_ID, merchant(OTHER_MERCHANT_ID, MerchantStatus.ACTIVE, "50000000"));
    }

    private static MerchantSnapshot merchant(UUID id, MerchantStatus status, String limit) {
        return new MerchantSnapshot(id, status, "VND", Money.of(limit, "VND"));
    }

    @Test
    @DisplayName("a first request is accepted with 202 and the payment the caller will be able to read")
    void acceptsAFirstRequest() {
        CreatePaymentResult result = handler.handle(request().build());

        assertThat(result).isInstanceOf(CreatePaymentResult.Accepted.class);
        assertThat(result.responseStatus()).isEqualTo(202);

        PaymentAcceptance accepted = result.payment();
        assertThat(accepted.paymentId()).isEqualTo(PAYMENT_ID);
        assertThat(accepted.status()).isEqualTo(PaymentStatus.CREATED);
        assertThat(accepted.currency()).isEqualTo("VND");
        assertThat(accepted.createdAt()).isEqualTo(NOW);
        assertThat(accepted.amount()).isEqualByComparingTo("500000");
    }

    /** {@code NUMERIC(19,4)} is the stored shape, so the response and the stored replay body carry it too. */
    @Test
    @DisplayName("the accepted amount keeps the scale the money column uses")
    void keepsMoneyScale() {
        assertThat(handler.handle(request().amount("500000").build()).payment().amount().scale())
                .isEqualTo(Money.SCALE);
    }

    @Test
    @DisplayName("the payment is saved with the command's contents and the injected clock's timestamp")
    void savesThePayment() {
        handler.handle(request().build());

        assertThat(payments.saved()).hasSize(1);
        Payment saved = payments.saved().getFirst();

        assertThat(saved.id()).isEqualTo(PAYMENT_ID);
        assertThat(saved.merchantId()).isEqualTo(MERCHANT_ID);
        assertThat(saved.merchantReference()).isEqualTo(MERCHANT_REFERENCE);
        assertThat(saved.idempotencyKey()).isEqualTo(IDEMPOTENCY_KEY);
        assertThat(saved.amount()).isEqualTo(Money.of("500000", "VND"));
        assertThat(saved.status()).isEqualTo(PaymentStatus.CREATED);
        assertThat(saved.createdAt()).isEqualTo(NOW);
        assertThat(saved.recordedStatusChanges()).hasSize(1);
    }

    /**
     * The merchant id comes from the token, never from the body — AGENTS.md section 8. The command is the
     * only place it can come from here, and this pins that the handler does not read it from anywhere else.
     */
    @Test
    @DisplayName("the payment belongs to the merchant in the command, not to any merchant in its contents")
    void scopesThePaymentToTheCommandsMerchant() {
        handler.handle(request().merchant(OTHER_MERCHANT_ID).build());

        assertThat(payments.saved().getFirst().merchantId()).isEqualTo(OTHER_MERCHANT_ID);
    }

    @Test
    @DisplayName("one payment.created v1 event is appended for the created payment")
    void appendsTheCreatedEvent() {
        handler.handle(request().build());

        assertThat(outbox.appended()).hasSize(1);
        FakeOutboxAppender.Appended appended = outbox.appended().getFirst();

        assertThat(appended.type()).isEqualTo(PaymentEvents.PAYMENT_CREATED);
        assertThat(appended.type().name()).isEqualTo("payment.created");
        assertThat(appended.type().version()).isEqualTo(1);
        assertThat(appended.topic()).isEqualTo(PayFlowTopics.PAYMENT_EVENTS);
        assertThat(appended.occurredAt()).isEqualTo(NOW);

        // The aggregate id is what orders the topic. A payment's events must share a partition, which they
        // only do if every one of them is keyed by the payment id.
        assertThat(appended.aggregateId()).isEqualTo(PAYMENT_ID.toString());

        assertThat(appended.data())
                .isEqualTo(
                        new PaymentCreatedData(
                                PAYMENT_ID,
                                MERCHANT_ID,
                                CUSTOMER_ID,
                                SOURCE_ACCOUNT_ID,
                                Money.of("500000", "VND").amount(),
                                "VND",
                                NOW));
    }

    /**
     * The event carries no {@code description} and no {@code metadata}. Those are merchant-supplied text, and
     * AGENTS.md section 9 keeps them out of anything that is logged or republished.
     */
    @Test
    @DisplayName("the event carries no merchant-supplied free text")
    void keepsMerchantTextOutOfTheEvent() {
        handler.handle(
                request()
                        .description("ghi chú nội bộ ZZTOP-DESCRIPTION")
                        .metadata(Map.of("note", "ZZTOP-METADATA"))
                        .build());

        assertThat(outbox.appended().getFirst().data().toString())
                .doesNotContain("ZZTOP-DESCRIPTION", "ZZTOP-METADATA");
    }

    /**
     * The order is the whole reason concurrent duplicates get a replay instead of a spurious 409. Two
     * requests with the same key normally carry the same merchant reference too, so both unique indexes
     * could fire; the first insert decides which one does.
     */
    @Test
    @DisplayName("the idempotency record is written before the payment, and the event last")
    void writesInTheOrderThatMakesRacesReplayable() {
        handler.handle(request().build());

        assertThat(journal).containsExactly("idempotency.record", "payment.save", "outbox.append");
    }

    @Test
    @DisplayName("the payment, its record and its event are written inside one committed transaction")
    void usesOneTransaction() {
        handler.handle(request().build());

        assertThat(transactionManager.started()).isEqualTo(1);
        assertThat(transactionManager.committed()).isEqualTo(1);
        assertThat(transactionManager.rolledBack()).isZero();
    }

    @Test
    @DisplayName("the stored replay body is the response that was returned")
    void storesTheResponseItReturned() {
        CreatePaymentResult result = handler.handle(request().build());

        IdempotentResponse stored = idempotency.stored(SCOPE, IDEMPOTENCY_KEY);
        assertThat(stored.body()).isEqualTo(result.payment());
        assertThat(stored.responseStatus()).isEqualTo(202);
        assertThat(stored.resourceId()).isEqualTo(PAYMENT_ID);
        assertThat(stored.requestHash()).isEqualTo(RequestFingerprint.of(request().build()));
    }

    @Test
    @DisplayName("the stored response expires one replay window after it was created")
    void storesTheReplayWindow() {
        handler.handle(request().build());

        assertThat(idempotency.expiresAt(SCOPE, IDEMPOTENCY_KEY))
                .isEqualTo(NOW.plus(CreatePaymentHandler.REPLAY_WINDOW));
    }

    /**
     * Spec 14.2, and the case a client hits in production: the response was lost, so it sends the same
     * request again. It must get the same payment back, not a second one.
     */
    @Test
    @DisplayName("an identical retry returns the stored response and creates nothing")
    void replaysAnIdenticalRetry() {
        CreatePaymentResult first = handler.handle(request().build());
        journal.clear();

        CreatePaymentResult second = handler.handle(request().build());

        assertThat(second).isInstanceOf(CreatePaymentResult.Replayed.class);
        assertThat(second.responseStatus()).isEqualTo(202);
        assertThat(second.payment()).isEqualTo(first.payment());
        assertThat(second.payment().paymentId()).isEqualTo(first.payment().paymentId());

        assertThat(journal).isEmpty();
        assertThat(payments.saved()).hasSize(1);
        assertThat(outbox.appended()).hasSize(1);
    }

    /** Scenario E in the spec: three identical requests, one payment, three identical payment ids. */
    @Test
    @DisplayName("three identical requests produce one payment")
    void replaysEveryFurtherRetry() {
        List<UUID> returned =
                List.of(
                        handler.handle(request().build()).payment().paymentId(),
                        handler.handle(request().build()).payment().paymentId(),
                        handler.handle(request().build()).payment().paymentId());

        assertThat(returned).containsExactly(PAYMENT_ID, PAYMENT_ID, PAYMENT_ID);
        assertThat(payments.saved()).hasSize(1);
        assertThat(outbox.appended()).hasSize(1);
        assertThat(ids.calls()).isEqualTo(1);
    }

    /** A replay is answered from a read. Opening a write transaction for it would be work nobody needs. */
    @Test
    @DisplayName("a replay opens no transaction")
    void replaysWithoutATransaction() {
        handler.handle(request().build());
        transactionManager.reset();

        handler.handle(request().build());

        assertThat(transactionManager.started()).isZero();
    }

    /**
     * Spec 14.2: the same key with a different body is the one case where the client has made a mistake, and
     * silently returning the first payment would hide it — the second payment would simply never exist.
     */
    @Test
    @DisplayName("the same key with a different request is a conflict")
    void rejectsAKeyReusedForADifferentRequest() {
        handler.handle(request().build());

        assertThatExceptionOfType(IdempotencyConflictException.class)
                .isThrownBy(() -> handler.handle(request().amount("900000").build()))
                .satisfies(
                        conflict -> {
                            assertThat(conflict.scope()).isEqualTo(SCOPE);
                            assertThat(conflict.idempotencyKey()).isEqualTo(IDEMPOTENCY_KEY);
                        });

        assertThat(payments.saved()).hasSize(1);
    }

    @Test
    @DisplayName("changing only the metadata is still a different request")
    void detectsAConflictInMetadataAlone() {
        handler.handle(request().build());

        assertThatExceptionOfType(IdempotencyConflictException.class)
                .isThrownBy(
                        () ->
                                handler.handle(
                                        request().metadata(Map.of("orderId", "ORDER-2026-00002")).build()));
    }

    /**
     * One merchant's key must never answer another's request. The scope carries the merchant id precisely so
     * that two merchants can both use {@code "retry-1"} without ever seeing each other's payments.
     */
    @Test
    @DisplayName("two merchants can use the same idempotency key independently")
    void keepsIdempotencyKeysPerMerchant() {
        handler.handle(request().merchant(MERCHANT_ID).build());
        CreatePaymentResult other = handler.handle(request().merchant(OTHER_MERCHANT_ID).build());

        assertThat(other).isInstanceOf(CreatePaymentResult.Accepted.class);
        assertThat(payments.saved()).hasSize(2);
        assertThat(outbox.appended()).hasSize(2);
    }

    /**
     * The concurrency case that matters: two requests with the same key arrive at once, one commits first,
     * and the loser's insert is refused by {@code uq_idempotency_records_scope_key}. The loser must answer
     * with the winner's response — the client sent one payment and there is exactly one.
     */
    @Test
    @DisplayName("losing the race to a concurrent duplicate returns the winner's response")
    void recoversFromALostRace() {
        CreatePaymentCommand command = request().build();
        idempotency.loseNextRaceTo(winnerResponse(RequestFingerprint.of(command)));

        CreatePaymentResult result = handler.handle(command);

        assertThat(result).isInstanceOf(CreatePaymentResult.Replayed.class);
        assertThat(result.payment().paymentId()).isEqualTo(WINNER_PAYMENT_ID);
        assertThat(result.responseStatus()).isEqualTo(202);

        // The loser's own transaction produced nothing.
        assertThat(payments.saved()).isEmpty();
        assertThat(outbox.appended()).isEmpty();
        assertThat(transactionManager.rolledBack()).isEqualTo(1);
        assertThat(transactionManager.committed()).isZero();
    }

    @Test
    @DisplayName("losing the race to a different request is still a conflict")
    void reportsAConflictAfterLosingToADifferentRequest() {
        idempotency.loseNextRaceTo(winnerResponse("a-fingerprint-of-some-other-request"));

        assertThatExceptionOfType(IdempotencyConflictException.class)
                .isThrownBy(() -> handler.handle(request().build()));
    }

    /**
     * The key exists but has no response behind it, which should not be possible. Returning any payment here
     * would mean inventing one, so the failure is reported as it happened.
     */
    @Test
    @DisplayName("a lost race with no readable winner fails rather than inventing a response")
    void refusesToGuessAfterALostRace() {
        idempotency.loseNextRaceWithoutAWinner();

        assertThatExceptionOfType(ConcurrentIdempotentRequestException.class)
                .isThrownBy(() -> handler.handle(request().build()));
    }

    /**
     * The token authenticated a merchant that this service has never heard of. The boundary answers 403 for
     * this — deciding that is the API layer's job, but nothing may be written first.
     */
    @Test
    @DisplayName("an unknown merchant is refused before anything is written")
    void refusesAnUnknownMerchant() {
        UUID unknown = UUID.fromString("99999999-9999-4999-8999-999999999999");

        assertThatExceptionOfType(MerchantNotRegisteredException.class)
                .isThrownBy(() -> handler.handle(request().merchant(unknown).build()))
                .satisfies(failure -> assertThat(failure.merchantId()).isEqualTo(unknown));

        assertThat(journal).isEmpty();
        assertThat(transactionManager.rolledBack()).isEqualTo(1);
    }

    @Test
    @DisplayName("a merchant that may not transact is refused and leaves no idempotency record")
    void refusesASuspendedMerchant() {
        catalog.put(MERCHANT_ID, merchant(MERCHANT_ID, MerchantStatus.SUSPENDED, "50000000"));

        assertThatExceptionOfType(MerchantNotAcceptingPaymentsException.class)
                .isThrownBy(() -> handler.handle(request().build()));

        // Nothing recorded: a refused request must not spend the client's idempotency key, or a fixed and
        // retried request would come back as a conflict.
        assertThat(idempotency.find(SCOPE, IDEMPOTENCY_KEY)).isEmpty();
        assertThat(journal).isEmpty();
    }

    @Test
    @DisplayName("an amount over the merchant's limit is refused")
    void refusesAnAmountOverTheMerchantLimit() {
        assertThatExceptionOfType(PaymentLimitExceededException.class)
                .isThrownBy(() -> handler.handle(request().amount("50000001").build()));

        assertThat(journal).isEmpty();
    }

    @Test
    @DisplayName("an unsupported currency is refused")
    void refusesAnUnsupportedCurrency() {
        assertThatExceptionOfType(UnsupportedCurrencyException.class)
                .isThrownBy(() -> handler.handle(request().currency("USD").build()));

        assertThat(journal).isEmpty();
    }

    /**
     * A reference the merchant has already used is the client's own duplicate, not a retry — it arrives with
     * a different idempotency key. It must surface as itself rather than be turned into a replay.
     */
    @Test
    @DisplayName("a duplicate merchant reference surfaces unchanged and appends no event")
    void propagatesADuplicateMerchantReference() {
        payments.failWith(
                new DuplicateMerchantReferenceException(
                        MERCHANT_ID, MERCHANT_REFERENCE, new RuntimeException("uq_payments_merchant_reference")));

        assertThatExceptionOfType(DuplicateMerchantReferenceException.class)
                .isThrownBy(() -> handler.handle(request().build()))
                .satisfies(
                        failure -> assertThat(failure.merchantReference()).isEqualTo(MERCHANT_REFERENCE));

        assertThat(outbox.appended()).isEmpty();
        assertThat(transactionManager.rolledBack()).isEqualTo(1);
    }

    private static IdempotentResponse winnerResponse(String requestHash) {
        return new IdempotentResponse(
                requestHash,
                WINNER_PAYMENT_ID,
                202,
                new PaymentAcceptance(
                        WINNER_PAYMENT_ID,
                        PaymentStatus.CREATED,
                        Money.of("500000", "VND").amount(),
                        "VND",
                        NOW));
    }

    private static final class FakePaymentRepository implements PaymentRepository {

        private final List<String> journal;
        private final List<Payment> saved = new ArrayList<>();
        private RuntimeException failure;

        private FakePaymentRepository(List<String> journal) {
            this.journal = journal;
        }

        @Override
        public void save(Payment payment) {
            journal.add("payment.save");
            if (failure != null) {
                throw failure;
            }
            saved.add(payment);
        }

        @Override
        public Optional<Payment> find(UUID paymentId, UUID merchantId) {
            return saved.stream()
                    .filter(payment -> payment.id().equals(paymentId))
                    .filter(payment -> payment.merchantId().equals(merchantId))
                    .findFirst();
        }

        void failWith(RuntimeException failure) {
            this.failure = failure;
        }

        List<Payment> saved() {
            return saved;
        }
    }

    /**
     * Keyed by scope and key together, exactly as {@code uq_idempotency_records_scope_key} is, and it refuses
     * a second insert for the same pair the same way that index does.
     */
    private static final class FakeIdempotencyStore implements IdempotencyStore {

        private final List<String> journal;
        private final Map<String, IdempotentResponse> rows = new HashMap<>();
        private final Map<String, Instant> expiries = new HashMap<>();
        private IdempotentResponse raceWinner;
        private boolean loseRace;

        private FakeIdempotencyStore(List<String> journal) {
            this.journal = journal;
        }

        @Override
        public Optional<IdempotentResponse> find(String scope, String idempotencyKey) {
            return Optional.ofNullable(rows.get(rowKey(scope, idempotencyKey)));
        }

        @Override
        public void record(
                String scope, String idempotencyKey, IdempotentResponse response, Instant expiresAt) {

            journal.add("idempotency.record");
            String rowKey = rowKey(scope, idempotencyKey);

            if (loseRace) {
                loseRace = false;
                if (raceWinner != null) {
                    // The winner's transaction committed while ours was open, which is what makes its row
                    // both the reason we failed and readable immediately afterwards.
                    rows.put(rowKey, raceWinner);
                }
                throw violation(scope, idempotencyKey);
            }
            if (rows.putIfAbsent(rowKey, response) != null) {
                throw violation(scope, idempotencyKey);
            }
            expiries.put(rowKey, expiresAt);
        }

        /** The next {@code record} fails as if {@code winner} had just committed the same key. */
        void loseNextRaceTo(IdempotentResponse winner) {
            this.loseRace = true;
            this.raceWinner = winner;
        }

        /** The next {@code record} fails with no row readable afterwards, which should not happen. */
        void loseNextRaceWithoutAWinner() {
            this.loseRace = true;
            this.raceWinner = null;
        }

        IdempotentResponse stored(String scope, String idempotencyKey) {
            return rows.get(rowKey(scope, idempotencyKey));
        }

        Instant expiresAt(String scope, String idempotencyKey) {
            return expiries.get(rowKey(scope, idempotencyKey));
        }

        private static ConcurrentIdempotentRequestException violation(String scope, String key) {
            return new ConcurrentIdempotentRequestException(
                    scope, key, new RuntimeException("uq_idempotency_records_scope_key"));
        }

        private static String rowKey(String scope, String idempotencyKey) {
            return scope + "\u0000" + idempotencyKey;
        }
    }

    private static final class FakeOutboxAppender implements OutboxAppender {

        record Appended(
                EventType type, String topic, String aggregateId, Instant occurredAt, Object data) {}

        private final List<String> journal;
        private final List<Appended> appended = new ArrayList<>();

        private FakeOutboxAppender(List<String> journal) {
            this.journal = journal;
        }

        @Override
        public <T> UUID append(
                EventType type, String topic, String aggregateId, Instant occurredAt, T data) {

            journal.add("outbox.append");
            appended.add(new Appended(type, topic, aggregateId, occurredAt, data));
            return UUID.randomUUID();
        }

        List<Appended> appended() {
            return appended;
        }
    }

    /** Counts calls so that a replay can be shown not to have generated an id it then threw away. */
    private static final class CountingIdGenerator implements IdGenerator {

        private final UUID id;
        private int calls;

        private CountingIdGenerator(UUID id) {
            this.id = id;
        }

        @Override
        public UUID newId() {
            calls++;
            return id;
        }

        int calls() {
            return calls;
        }
    }

    /**
     * Counts boundaries. It deliberately does not undo anything: a fake that pretended to roll back would
     * make these tests look like proof of atomicity, which only a real database can give.
     */
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

        void reset() {
            started = 0;
            committed = 0;
            rolledBack = 0;
        }

        int started() {
            return started;
        }

        int committed() {
            return committed;
        }

        int rolledBack() {
            return rolledBack;
        }
    }
}
