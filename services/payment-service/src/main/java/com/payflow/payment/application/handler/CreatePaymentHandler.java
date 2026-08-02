package com.payflow.payment.application.handler;

import com.payflow.events.PayFlowTopics;
import com.payflow.events.payment.PaymentCreatedData;
import com.payflow.events.payment.PaymentEvents;
import com.payflow.payment.application.CreatePaymentResult;
import com.payflow.payment.application.PaymentAcceptance;
import com.payflow.payment.application.command.CreatePaymentCommand;
import com.payflow.payment.application.exception.ConcurrentIdempotentRequestException;
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
import com.payflow.payment.application.port.PaymentSagaStore;
import com.payflow.payment.application.saga.SagaRecoverySettings;
import com.payflow.payment.domain.model.MerchantSnapshot;
import com.payflow.payment.domain.model.Money;
import com.payflow.payment.domain.model.Payment;
import com.payflow.payment.domain.model.PaymentIntake;
import com.payflow.payment.domain.model.PaymentSaga;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Accepts a payment: exactly once per idempotency key, with its event, in one transaction.
 *
 * <h2>Why this class is not annotated {@code @Transactional}</h2>
 *
 * <p>Two reasons, and both are the kind that produce a subtly broken system rather than a failing test.
 *
 * <p>The first is that recovering from a lost race requires reading the winner's row, and a transaction
 * that has just failed a unique constraint is marked rollback-only — every further statement on it fails.
 * The read has to happen outside the transaction that failed, which means the transaction boundary must be
 * inside this method rather than around it.
 *
 * <p>The second is that a {@code @Transactional} method calling another method of the same class goes
 * through {@code this}, not the proxy, so the inner boundary would silently not exist. A
 * {@link TransactionTemplate} makes the boundary a visible statement instead of a property of how the call
 * was routed.
 *
 * <h2>Order of writes inside the transaction</h2>
 *
 * <p>The idempotency record is inserted first, before the payment. Two concurrent requests carrying the
 * same key normally also carry the same {@code merchantReference}, so both unique indexes are in play; the
 * one that fires decides which exception the caller gets. Inserting the idempotency row first makes the
 * race serialise on {@code uq_idempotency_records_scope_key}, whose answer is "replay the winner's
 * response". If the payment went in first, the same race would surface as a duplicate merchant reference —
 * a 409 for a client that did nothing wrong.
 */
@Service
public class CreatePaymentHandler {

    /**
     * How long a stored response stays replayable.
     *
     * <p>A day covers any sane client retry policy, including a queue that was down overnight, and bounds
     * how long a merchant's request bodies are kept. Nothing deletes expired rows yet — the cleanup job is
     * outside Phase 1A — so this is a retention promise the schema records, not one it enforces.
     */
    static final Duration REPLAY_WINDOW = Duration.ofHours(24);

    private final MerchantCatalog merchants;
    private final PaymentRepository payments;
    private final PaymentSagaStore sagas;
    private final IdempotencyStore idempotency;
    private final OutboxAppender outbox;
    private final IdGenerator ids;
    private final Clock clock;
    private final SagaRecoverySettings sagaSettings;
    private final TransactionTemplate transactions;

    public CreatePaymentHandler(
            MerchantCatalog merchants,
            PaymentRepository payments,
            PaymentSagaStore sagas,
            IdempotencyStore idempotency,
            OutboxAppender outbox,
            IdGenerator ids,
            Clock clock,
            SagaRecoverySettings sagaSettings,
            TransactionTemplate transactions) {

        this.merchants = merchants;
        this.payments = payments;
        this.sagas = sagas;
        this.idempotency = idempotency;
        this.outbox = outbox;
        this.ids = ids;
        this.clock = clock;
        this.sagaSettings = sagaSettings;
        this.transactions = transactions;
    }

    /**
     * @throws MerchantNotRegisteredException if the token's merchant is not in the catalog
     * @throws IdempotencyConflictException if the key was used by a different request
     * @throws com.payflow.payment.application.exception.DuplicateMerchantReferenceException if the merchant
     *     already used this reference
     * @throws com.payflow.payment.domain.exception.PaymentDomainException if the merchant may not transact,
     *     does not settle in this currency, or the amount is over its per-payment limit
     */
    public CreatePaymentResult handle(CreatePaymentCommand command) {
        String scope = IdempotencyScope.createPayment(command.merchantId());
        String fingerprint = RequestFingerprint.of(command);

        // Read before opening a transaction. A retry is the expected case for a client with a queue behind
        // it, and answering it should not take a write lock on anything.
        var stored = idempotency.find(scope, command.idempotencyKey());
        if (stored.isPresent()) {
            return replay(stored.get(), fingerprint, scope, command.idempotencyKey());
        }

        try {
            return transactions.execute(status -> create(command, scope, fingerprint));
        } catch (ConcurrentIdempotentRequestException lostTheRace) {
            // The other request has committed by the time the constraint reported the conflict, so its
            // response is readable now. If it somehow is not, the key exists with no response behind it
            // and there is nothing honest left to return.
            return idempotency
                    .find(scope, command.idempotencyKey())
                    .map(winner -> replay(winner, fingerprint, scope, command.idempotencyKey()))
                    .orElseThrow(() -> lostTheRace);
        }
    }

    private CreatePaymentResult create(
            CreatePaymentCommand command, String scope, String fingerprint) {

        Instant now = clock.instant();

        MerchantSnapshot merchant =
                merchants
                        .findById(command.merchantId())
                        .orElseThrow(() -> new MerchantNotRegisteredException(command.merchantId()));

        PaymentIntake intake =
                new PaymentIntake(
                        ids.newId(),
                        command.customerId(),
                        command.sourceAccountId(),
                        command.merchantReference(),
                        command.idempotencyKey(),
                        new Money(command.amount(), command.currency()),
                        command.description(),
                        command.metadata(),
                        now);

        Payment payment = Payment.create(merchant, intake);
        // The public 202 contract returns the initial CREATED snapshot. The stored aggregate advances
        // to RISK_CHECKING in the same transaction that appends payment.created, so a later Risk result
        // cannot arrive while Payment still claims it was never submitted.
        PaymentAcceptance acceptance = PaymentAcceptance.of(payment);
        payment.submitForRisk(now);

        idempotency.record(
                scope,
                command.idempotencyKey(),
                new IdempotentResponse(
                        fingerprint,
                        payment.id(),
                        CreatePaymentResult.Accepted.STATUS,
                        acceptance),
                now.plus(REPLAY_WINDOW));

        payments.save(payment);
        sagas.add(PaymentSaga.start(
                ids.newId(),
                payment.id(),
                now.plus(sagaSettings.stepTimeout()),
                now));

        outbox.append(
                PaymentEvents.PAYMENT_CREATED,
                PayFlowTopics.PAYMENT_EVENTS,
                payment.id().toString(),
                now,
                new PaymentCreatedData(
                        payment.id(),
                        payment.merchantId(),
                        payment.customerId(),
                        payment.sourceAccountId(),
                        payment.amount().amount(),
                        payment.amount().currency(),
                        payment.createdAt()));

        return new CreatePaymentResult.Accepted(acceptance);
    }

    private CreatePaymentResult replay(
            IdempotentResponse stored, String fingerprint, String scope, String idempotencyKey) {

        if (!stored.matches(fingerprint)) {
            throw new IdempotencyConflictException(scope, idempotencyKey);
        }
        return new CreatePaymentResult.Replayed(stored.body(), stored.responseStatus());
    }
}
