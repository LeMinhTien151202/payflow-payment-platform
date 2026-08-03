package com.payflow.payment.application.handler;

import com.payflow.events.PayFlowTopics;
import com.payflow.events.refund.RefundEvents;
import com.payflow.events.refund.RefundRequestedData;
import com.payflow.payment.application.CreateRefundResult;
import com.payflow.payment.application.RefundAcceptance;
import com.payflow.payment.application.command.CreateRefundCommand;
import com.payflow.payment.application.exception.ConcurrentIdempotentRequestException;
import com.payflow.payment.application.exception.IdempotencyConflictException;
import com.payflow.payment.application.exception.PaymentNotFoundException;
import com.payflow.payment.application.idempotency.IdempotencyScope;
import com.payflow.payment.application.idempotency.RefundIdempotentResponse;
import com.payflow.payment.application.idempotency.RequestFingerprint;
import com.payflow.payment.application.port.IdGenerator;
import com.payflow.payment.application.port.OutboxAppender;
import com.payflow.payment.application.port.RefundIdempotencyStore;
import com.payflow.payment.application.port.RefundPaymentStore;
import com.payflow.payment.application.port.RefundRepository;
import com.payflow.payment.domain.model.Money;
import com.payflow.payment.domain.model.Payment;
import com.payflow.payment.domain.model.Refund;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Dự trữ payment capacity, tạo refund và append workflow event của nó một cách atomic. */
@Service
public class CreateRefundHandler {

    static final Duration REPLAY_WINDOW = Duration.ofHours(24);

    private final RefundPaymentStore payments;
    private final RefundRepository refunds;
    private final RefundIdempotencyStore idempotency;
    private final OutboxAppender outbox;
    private final IdGenerator ids;
    private final Clock clock;
    private final TransactionTemplate transactions;

    public CreateRefundHandler(
            RefundPaymentStore payments,
            RefundRepository refunds,
            RefundIdempotencyStore idempotency,
            OutboxAppender outbox,
            IdGenerator ids,
            Clock clock,
            TransactionTemplate transactions) {
        this.payments = payments;
        this.refunds = refunds;
        this.idempotency = idempotency;
        this.outbox = outbox;
        this.ids = ids;
        this.clock = clock;
        this.transactions = transactions;
    }

    public CreateRefundResult handle(CreateRefundCommand command) {
        String scope = IdempotencyScope.createRefund(command.merchantId());
        String fingerprint = RequestFingerprint.of(command);

        var stored = idempotency.find(scope, command.idempotencyKey());
        if (stored.isPresent()) {
            return replay(stored.get(), fingerprint, scope, command.idempotencyKey());
        }

        try {
            return transactions.execute(status -> create(command, scope, fingerprint));
        } catch (ConcurrentIdempotentRequestException lostRace) {
            return idempotency
                    .find(scope, command.idempotencyKey())
                    .map(winner -> replay(winner, fingerprint, scope, command.idempotencyKey()))
                    .orElseThrow(() -> lostRace);
        }
    }

    private CreateRefundResult create(
            CreateRefundCommand command, String scope, String fingerprint) {
        Instant now = clock.instant();
        Payment payment = payments
                .findForRefund(command.paymentId(), command.merchantId())
                .orElseThrow(() ->
                        new PaymentNotFoundException(command.paymentId(), command.merchantId()));

        // Một đợt retry trên cùng payment có thể đã chờ sau row lock của request chiến thắng. Đọc lại sau khi lấy được
        // lock để nó replay mà không cần dự trữ tạm thời capacity vốn sẽ bị rollback ngay sau đó.
        var winner = idempotency.find(scope, command.idempotencyKey());
        if (winner.isPresent()) {
            return replay(winner.get(), fingerprint, scope, command.idempotencyKey());
        }

        Money requested = new Money(command.amount(), payment.amount().currency());
        payment.reserveRefund(requested, now);

        Refund refund = Refund.create(
                ids.newId(),
                payment.id(),
                payment.merchantId(),
                command.idempotencyKey(),
                requested,
                command.reason(),
                command.actorId(),
                now);
        RefundAcceptance acceptance = RefundAcceptance.of(refund);

        idempotency.record(
                scope,
                command.idempotencyKey(),
                new RefundIdempotentResponse(
                        fingerprint,
                        refund.id(),
                        CreateRefundResult.Accepted.STATUS,
                        acceptance),
                now.plus(REPLAY_WINDOW));
        payments.updateRefundState(payment);
        refunds.save(refund);
        outbox.append(
                RefundEvents.REFUND_REQUESTED,
                PayFlowTopics.REFUND_EVENTS,
                payment.id().toString(),
                now,
                new RefundRequestedData(
                        refund.id(),
                        payment.id(),
                        payment.merchantId(),
                        payment.customerId(),
                        payment.sourceAccountId(),
                        requested.amount(),
                        requested.currency(),
                        now));

        return new CreateRefundResult.Accepted(acceptance);
    }

    private CreateRefundResult replay(
            RefundIdempotentResponse stored,
            String fingerprint,
            String scope,
            String idempotencyKey) {
        if (!stored.matches(fingerprint)) {
            throw new IdempotencyConflictException(scope, idempotencyKey);
        }
        return new CreateRefundResult.Replayed(stored.body(), stored.responseStatus());
    }
}
