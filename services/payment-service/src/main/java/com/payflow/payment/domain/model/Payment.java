package com.payflow.payment.domain.model;

import com.payflow.payment.domain.exception.CurrencyNotAcceptedException;
import com.payflow.payment.domain.exception.IllegalStatusTransitionException;
import com.payflow.payment.domain.exception.MerchantNotAcceptingPaymentsException;
import com.payflow.payment.domain.exception.PaymentLimitExceededException;
import com.payflow.payment.domain.exception.UnexpectedPaymentStatusException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * A payment: the aggregate that owns its own status and the record of how it got there.
 *
 * <p>Plain Java. No Spring, no JPA, no Jackson — the dependency rule in ARCHITECTURE.md section 5 is
 * {@code domain -> Java standard library only}. The practical benefit is that every rule below can be
 * tested by calling a constructor, so the state machine is verified in milliseconds rather than
 * against a database.
 *
 * <p>Mutable by design, unlike the value objects around it. Status changes over a payment's life and
 * pretending otherwise would mean rebuilding the object on every step and losing track of which
 * instance is authoritative.
 *
 * <p>There is no {@code version} field. Optimistic locking is a persistence mechanism; the JPA entity
 * owns it, so the domain does not carry a column it cannot enforce.
 *
 * <p>Not thread-safe. One payment is loaded, changed, and committed inside a single transaction on a
 * single thread; concurrent access is prevented by the database, not by synchronisation here.
 */
public final class Payment {

    private final UUID id;
    private final UUID merchantId;
    private final UUID customerId;
    private final UUID sourceAccountId;
    private final String merchantReference;
    private final String idempotencyKey;
    private final Money amount;
    private final String description;
    private final Map<String, String> metadata;
    private final Instant createdAt;

    private PaymentStatus status;
    private Instant updatedAt;

    /**
     * Status changes made during this unit of work, in order.
     *
     * <p>The aggregate records them; the application layer is what writes them, in the same transaction
     * as the payment itself. Keeping the list here rather than having callers remember to build history
     * rows is what makes it impossible to change a status without leaving a trace.
     */
    private final List<PaymentStatusChange> recordedChanges = new ArrayList<>();

    private Payment(
            UUID id,
            UUID merchantId,
            PaymentIntake intake,
            PaymentStatus status,
            Instant updatedAt) {

        this.id = id;
        this.merchantId = merchantId;
        this.customerId = intake.customerId();
        this.sourceAccountId = intake.sourceAccountId();
        this.merchantReference = intake.merchantReference();
        this.idempotencyKey = intake.idempotencyKey();
        this.amount = intake.amount();
        this.description = intake.description();
        this.metadata = intake.metadata();
        this.createdAt = intake.createdAt();
        this.status = status;
        this.updatedAt = updatedAt;
    }

    /**
     * Accepts a payment, or refuses it.
     *
     * <p>The three rules here are the ones spec 7.4 lists as validation and that only the domain can
     * apply, because each needs the merchant and the amount together. Uniqueness of
     * {@code merchantReference} is not among them: that is a fact about all the merchant's other
     * payments, which this object cannot see, and it is enforced by a unique index rather than by a
     * read that a concurrent request would race.
     *
     * @param merchant an immutable snapshot taken before the decision
     * @throws MerchantNotAcceptingPaymentsException if the merchant may not transact
     * @throws CurrencyNotAcceptedException if the merchant does not settle in the requested currency
     * @throws PaymentLimitExceededException if the amount is over the merchant's per-payment ceiling
     */
    public static Payment create(MerchantSnapshot merchant, PaymentIntake intake) {
        Objects.requireNonNull(merchant, "merchant");
        Objects.requireNonNull(intake, "intake");

        if (!merchant.canAcceptPayments()) {
            throw new MerchantNotAcceptingPaymentsException(merchant.id(), merchant.status());
        }
        if (!intake.amount().currency().equals(merchant.defaultCurrency())) {
            throw new CurrencyNotAcceptedException(
                    intake.amount().currency(), merchant.defaultCurrency());
        }
        // Strictly greater: a payment exactly at the limit is allowed, because a limit of 50,000,000
        // that rejects 50,000,000 is a limit of 49,999,999.9999 and nobody documents it that way.
        if (intake.amount().isGreaterThan(merchant.maxTransactionAmount())) {
            throw new PaymentLimitExceededException(
                    intake.amount(), merchant.maxTransactionAmount());
        }

        Payment payment =
                new Payment(
                        intake.paymentId(),
                        merchant.id(),
                        intake,
                        PaymentStatus.initial(),
                        intake.createdAt());

        payment.recordedChanges.add(
                PaymentStatusChange.initial(PaymentStatus.initial(), intake.createdAt()));

        return payment;
    }

    /**
     * Rebuilds a payment from storage.
     *
     * <p>Runs none of the acceptance rules. A payment that was valid when it was accepted stays valid:
     * re-checking the merchant's current status here would make a stored payment unreadable the moment
     * its merchant was suspended, which is exactly when someone needs to look at it.
     *
     * <p>Returns an aggregate with no recorded changes — the history already in the database is not
     * something this instance should write again.
     */
    public static Payment rehydrate(
            UUID id,
            UUID merchantId,
            PaymentIntake intake,
            PaymentStatus status,
            Instant updatedAt) {

        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(merchantId, "merchantId");
        Objects.requireNonNull(intake, "intake");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(updatedAt, "updatedAt");

        return new Payment(id, merchantId, intake, status, updatedAt);
    }

    /**
     * Moves the payment to {@code target}, or refuses the move.
     *
     * @param reasonCode why, for the history row; may be null when the transition speaks for itself
     * @param at when it happened, from an injected {@code Clock}
     * @throws IllegalStatusTransitionException if the state machine has no such edge
     */
    public void transitionTo(PaymentStatus target, String reasonCode, Instant at) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(at, "at");

        if (!status.canTransitionTo(target)) {
            throw new IllegalStatusTransitionException(id, status, target);
        }

        recordedChanges.add(new PaymentStatusChange(status, target, reasonCode, at));
        status = target;
        updatedAt = at;
    }

    /**
     * Marks that the committed {@code payment.created} outbox event has handed this payment to Risk.
     * The caller still returns the immutable CREATED acceptance snapshot required by the public API.
     */
    public void submitForRisk(Instant at) {
        transitionTo(PaymentStatus.RISK_CHECKING, "RISK_SUBMITTED", at);
    }

    /**
     * Applies the decision semantics fixed by ADR-016 without performing any I/O.
     *
     * <p>The application consumer will persist the transition and its outgoing outbox event in one
     * local transaction after OD-007 is implemented. REVIEW_REQUIRED deliberately records no status
     * transition: the published state machine has no review status, so the payment remains
     * RISK_CHECKING and no money is touched.
     */
    public PaymentRiskAction applyRiskDecision(PaymentRiskDecision decision, Instant at) {
        Objects.requireNonNull(decision, "decision");
        Objects.requireNonNull(at, "at");
        requireStatus(PaymentStatus.RISK_CHECKING, "risk decision");

        return switch (decision) {
            case APPROVED -> {
                transitionTo(PaymentStatus.RESERVING_FUNDS, "RISK_APPROVED", at);
                yield PaymentRiskAction.REQUEST_FUNDS_RESERVATION;
            }
            case REJECTED -> {
                transitionTo(PaymentStatus.RISK_REJECTED, "RISK_REJECTED", at);
                yield PaymentRiskAction.PUBLISH_PAYMENT_FAILED;
            }
            case REVIEW_REQUIRED -> PaymentRiskAction.AWAIT_MANUAL_REVIEW;
        };
    }

    private void requireStatus(PaymentStatus expected, String operation) {
        if (status != expected) {
            throw new UnexpectedPaymentStatusException(id, expected, status, operation);
        }
    }

    /** True when the payment can no longer move. See {@link PaymentStatus#isTerminal()}. */
    public boolean isTerminal() {
        return status.isTerminal();
    }

    /** Status changes made on this instance, oldest first. Empty for a rehydrated payment. */
    public List<PaymentStatusChange> recordedStatusChanges() {
        return List.copyOf(recordedChanges);
    }

    public UUID id() {
        return id;
    }

    public UUID merchantId() {
        return merchantId;
    }

    public UUID customerId() {
        return customerId;
    }

    public UUID sourceAccountId() {
        return sourceAccountId;
    }

    public String merchantReference() {
        return merchantReference;
    }

    public String idempotencyKey() {
        return idempotencyKey;
    }

    public Money amount() {
        return amount;
    }

    public String description() {
        return description;
    }

    /** Immutable: the map came from {@link PaymentIntake}, which copied it. */
    public Map<String, String> metadata() {
        return metadata;
    }

    public PaymentStatus status() {
        return status;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    /**
     * Identity is the id, not the field values.
     *
     * <p>An aggregate whose {@code equals} compared every field would report a payment as different
     * from itself after a status change. Two instances with the same id are the same payment, possibly
     * read at different times.
     */
    @Override
    public boolean equals(Object other) {
        return other instanceof Payment payment && id.equals(payment.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    /** Deliberately excludes description and metadata: both are merchant-supplied and never logged. */
    @Override
    public String toString() {
        return "Payment[id=" + id + ", merchantId=" + merchantId + ", amount=" + amount
                + ", status=" + status + "]";
    }
}
