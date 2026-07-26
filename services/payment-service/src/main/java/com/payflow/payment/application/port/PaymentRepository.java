package com.payflow.payment.application.port;

import com.payflow.payment.application.exception.ConcurrentIdempotentRequestException;
import com.payflow.payment.application.exception.DuplicateMerchantReferenceException;
import com.payflow.payment.domain.model.Payment;
import java.util.Optional;
import java.util.UUID;

/**
 * Persists the payment aggregate.
 *
 * <p>One repository per aggregate, not one per table: {@link #save} writes the payment and the status
 * history the aggregate recorded, because a status change without its history row is exactly the state
 * the history table exists to make impossible.
 *
 * <p>The two exceptions below are declared here rather than left as {@code DataAccessException}. Both
 * are business outcomes that happen to be detected by a unique index, and the caller has to tell them
 * apart: one is a client mistake and the other is a race whose correct answer is to replay. Deciding
 * which is which needs the constraint name, which only the adapter can see.
 */
public interface PaymentRepository {

    /**
     * Inserts the payment together with every status change it recorded.
     *
     * @throws DuplicateMerchantReferenceException if the merchant already used that reference
     * @throws ConcurrentIdempotentRequestException if a concurrent request with the same idempotency
     *     key committed a payment first
     */
    void save(Payment payment);

    /**
     * Finds a payment owned by a merchant.
     *
     * <p>The merchant is a parameter, not something the caller filters on afterwards. AGENTS.md section 8
     * requires ownership to be checked at the application boundary, and a signature that cannot be called
     * without naming the asking merchant is the version of that rule a reviewer cannot forget to apply. A
     * {@code findById(paymentId)} sitting next to it would eventually be called by someone who did not
     * realise the check was theirs to make.
     *
     * @return empty both when no such payment exists and when it belongs to another merchant — the caller
     *     must not be able to tell those apart, or the endpoint becomes a way to discover payment ids
     */
    Optional<Payment> find(UUID paymentId, UUID merchantId);
}
