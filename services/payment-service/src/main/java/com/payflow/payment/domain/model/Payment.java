package com.payflow.payment.domain.model;

import com.payflow.payment.domain.exception.CurrencyNotAcceptedException;
import com.payflow.payment.domain.exception.IllegalStatusTransitionException;
import com.payflow.payment.domain.exception.MerchantNotAcceptingPaymentsException;
import com.payflow.payment.domain.exception.PaymentLimitExceededException;
import com.payflow.payment.domain.exception.RefundCapacityExceededException;
import com.payflow.payment.domain.exception.RefundNotAllowedException;
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
    private final PaymentFeeSnapshot feeSnapshot;
    private final String description;
    private final Map<String, String> metadata;
    private final Instant createdAt;

    private PaymentStatus status;
    private Money totalRefundedAmount;
    private Money reservedRefundAmount;
    private Money totalFeeReversedAmount;
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
            PaymentFeeSnapshot feeSnapshot,
            PaymentStatus status,
            Money totalRefundedAmount,
            Money reservedRefundAmount,
            Money totalFeeReversedAmount,
            Instant updatedAt) {

        this.id = id;
        this.merchantId = merchantId;
        this.customerId = intake.customerId();
        this.sourceAccountId = intake.sourceAccountId();
        this.merchantReference = intake.merchantReference();
        this.idempotencyKey = intake.idempotencyKey();
        this.amount = intake.amount();
        this.feeSnapshot = Objects.requireNonNull(feeSnapshot, "feeSnapshot");
        this.description = intake.description();
        this.metadata = intake.metadata();
        this.createdAt = intake.createdAt();
        this.status = status;
        this.totalRefundedAmount = Objects.requireNonNull(totalRefundedAmount, "totalRefundedAmount");
        this.reservedRefundAmount = Objects.requireNonNull(reservedRefundAmount, "reservedRefundAmount");
        this.totalFeeReversedAmount =
                Objects.requireNonNull(totalFeeReversedAmount, "totalFeeReversedAmount");
        this.updatedAt = updatedAt;

        validateRefundFacts();
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
                        PaymentFeeSnapshot.calculate(merchant.feePolicy(), intake.amount()),
                        PaymentStatus.initial(),
                        Money.zero(intake.amount().currency()),
                        Money.zero(intake.amount().currency()),
                        Money.zero(intake.amount().currency()),
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
    public static Payment rehydrateLegacyNoFee(
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

        return new Payment(
                id,
                merchantId,
                intake,
                PaymentFeeSnapshot.calculate(FeePolicySnapshot.legacyNoFee(), intake.amount()),
                status,
                Money.zero(intake.amount().currency()),
                Money.zero(intake.amount().currency()),
                Money.zero(intake.amount().currency()),
                updatedAt);
    }

    public static Payment rehydrate(
            UUID id,
            UUID merchantId,
            PaymentIntake intake,
            PaymentFeeSnapshot feeSnapshot,
            PaymentStatus status,
            Money totalRefundedAmount,
            Money reservedRefundAmount,
            Money totalFeeReversedAmount,
            Instant updatedAt) {

        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(merchantId, "merchantId");
        Objects.requireNonNull(intake, "intake");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(updatedAt, "updatedAt");
        return new Payment(
                id,
                merchantId,
                intake,
                feeSnapshot,
                status,
                totalRefundedAmount,
                reservedRefundAmount,
                totalFeeReversedAmount,
                updatedAt);
    }

    /** Holds capacity before any asynchronous refund side effect starts. ADR-020. */
    public void reserveRefund(Money requested, Instant at) {
        Objects.requireNonNull(requested, "requested");
        Objects.requireNonNull(at, "at");
        requireRefundableStatus();
        requireRefundCurrency(requested);
        if (!requested.isPositive()) {
            throw new IllegalArgumentException("refund amount must be positive");
        }

        Money available = refundableAmount();
        if (requested.isGreaterThan(available)) {
            throw new RefundCapacityExceededException(id, requested, available);
        }
        reservedRefundAmount = reservedRefundAmount.plus(requested);
        updatedAt = at;
    }

    /** Moves reserved capacity to succeeded total and returns this refund's fee reversal. */
    public Money completeRefund(Money succeededAmount, Instant at) {
        Objects.requireNonNull(succeededAmount, "succeededAmount");
        Objects.requireNonNull(at, "at");
        requireRefundableStatus();
        requireRefundCurrency(succeededAmount);
        if (!succeededAmount.isPositive() || succeededAmount.isGreaterThan(reservedRefundAmount)) {
            throw new IllegalArgumentException("refund success must consume existing reserved capacity");
        }

        Money newReserved = reservedRefundAmount.minus(succeededAmount);
        Money newTotal = totalRefundedAmount.plus(succeededAmount);
        Money reversal =
                feeSnapshot.reversalDelta(amount, newTotal, totalFeeReversedAmount);
        Money newTotalFeeReversed = totalFeeReversedAmount.plus(reversal);

        reservedRefundAmount = newReserved;
        totalRefundedAmount = newTotal;
        totalFeeReversedAmount = newTotalFeeReversed;

        if (totalRefundedAmount.equals(amount)) {
            transitionTo(PaymentStatus.REFUNDED, "REFUND_SUCCEEDED", at);
        } else if (status == PaymentStatus.SUCCEEDED) {
            transitionTo(PaymentStatus.PARTIALLY_REFUNDED, "REFUND_PARTIALLY_SUCCEEDED", at);
        } else {
            updatedAt = at;
        }
        return reversal;
    }

    /** Releases capacity for a refund that reached a definitive failure. */
    public void releaseRefund(Money failedAmount, Instant at) {
        Objects.requireNonNull(failedAmount, "failedAmount");
        Objects.requireNonNull(at, "at");
        requireRefundCurrency(failedAmount);
        if (!failedAmount.isPositive() || failedAmount.isGreaterThan(reservedRefundAmount)) {
            throw new IllegalArgumentException("refund failure must release existing reserved capacity");
        }
        reservedRefundAmount = reservedRefundAmount.minus(failedAmount);
        updatedAt = at;
    }

    public Money refundableAmount() {
        return amount.minus(totalRefundedAmount).minus(reservedRefundAmount);
    }

    private void requireRefundableStatus() {
        if (status != PaymentStatus.SUCCEEDED && status != PaymentStatus.PARTIALLY_REFUNDED) {
            throw new RefundNotAllowedException(id, status);
        }
    }

    private void requireRefundCurrency(Money candidate) {
        if (!amount.currency().equals(candidate.currency())) {
            throw new IllegalArgumentException("refund currency differs from payment currency");
        }
    }

    private void validateRefundFacts() {
        requireRefundCurrency(totalRefundedAmount);
        requireRefundCurrency(reservedRefundAmount);
        if (!feeSnapshot.feeAmount().currency().equals(amount.currency())
                || !totalFeeReversedAmount.currency().equals(amount.currency())) {
            throw new IllegalArgumentException("fee/refund currency differs from payment currency");
        }
        if (feeSnapshot.feeAmount().isGreaterThan(amount)) {
            throw new IllegalArgumentException("fee amount exceeds payment amount");
        }
        if (totalRefundedAmount.plus(reservedRefundAmount).isGreaterThan(amount)) {
            throw new IllegalArgumentException("refund capacity exceeds payment amount");
        }
        if (totalFeeReversedAmount.isGreaterThan(feeSnapshot.feeAmount())) {
            throw new IllegalArgumentException("reversed fee exceeds original fee");
        }
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
     * local transaction. REVIEW_REQUIRED enters the explicit client-visible state fixed by ADR-018;
     * no money is touched and only an audited Saga resolution may resume it.
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
            case REVIEW_REQUIRED -> {
                transitionTo(
                        PaymentStatus.MANUAL_REVIEW_REQUIRED,
                        "RISK_REVIEW_REQUIRED",
                        at);
                yield PaymentRiskAction.AWAIT_MANUAL_REVIEW;
            }
        };
    }

    /** Account confirmed the reservation, so Ledger posting may begin. */
    public void confirmFundsReserved(Instant at) {
        requireStatus(PaymentStatus.RESERVING_FUNDS, "confirm reserved funds");
        transitionTo(PaymentStatus.PROCESSING, "FUNDS_RESERVED", at);
    }

    /** Account definitively rejected reservation before any journal was requested. */
    public void failFundsReservation(String reasonCode, Instant at) {
        Objects.requireNonNull(reasonCode, "reasonCode");
        requireStatus(PaymentStatus.RESERVING_FUNDS, "fail funds reservation");
        transitionTo(PaymentStatus.FAILED, reasonCode, at);
    }

    /** Stops automated processing without guessing a financial outcome. ADR-018. */
    public void requireManualReview(String reasonCode, Instant at) {
        Objects.requireNonNull(reasonCode, "reasonCode");
        if (status != PaymentStatus.RISK_CHECKING
                && status != PaymentStatus.RESERVING_FUNDS
                && status != PaymentStatus.PROCESSING) {
            throw new UnexpectedPaymentStatusException(
                    id,
                    PaymentStatus.MANUAL_REVIEW_REQUIRED,
                    status,
                    "enter manual review");
        }
        transitionTo(PaymentStatus.MANUAL_REVIEW_REQUIRED, reasonCode, at);
    }

    /** A committed pre-ledger release makes failure safe and final. */
    public void failAfterCompensation(String reasonCode, Instant at) {
        Objects.requireNonNull(reasonCode, "reasonCode");
        if (status != PaymentStatus.PROCESSING
                && status != PaymentStatus.MANUAL_REVIEW_REQUIRED) {
            throw new UnexpectedPaymentStatusException(
                    id, PaymentStatus.PROCESSING, status, "complete compensation");
        }
        transitionTo(PaymentStatus.FAILED, reasonCode, at);
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

    public PaymentFeeSnapshot feeSnapshot() {
        return feeSnapshot;
    }

    public Money totalRefundedAmount() {
        return totalRefundedAmount;
    }

    public Money reservedRefundAmount() {
        return reservedRefundAmount;
    }

    public Money totalFeeReversedAmount() {
        return totalFeeReversedAmount;
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
