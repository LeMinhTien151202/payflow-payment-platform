package com.payflow.payment.application.handler;

import com.payflow.events.PayFlowTopics;
import com.payflow.events.payment.PaymentCancelledData;
import com.payflow.events.payment.PaymentEvents;
import com.payflow.payment.application.CancelPaymentResult;
import com.payflow.payment.application.PaymentAcceptance;
import com.payflow.payment.application.command.CancelPaymentCommand;
import com.payflow.payment.application.exception.ConcurrentIdempotentRequestException;
import com.payflow.payment.application.exception.IdempotencyConflictException;
import com.payflow.payment.application.exception.PaymentNotFoundException;
import com.payflow.payment.application.idempotency.IdempotencyScope;
import com.payflow.payment.application.idempotency.IdempotentResponse;
import com.payflow.payment.application.idempotency.RequestFingerprint;
import com.payflow.payment.application.port.IdempotencyStore;
import com.payflow.payment.application.port.OutboxAppender;
import com.payflow.payment.application.port.PaymentSagaStore;
import com.payflow.payment.application.port.PaymentWorkflowStore;
import com.payflow.payment.application.saga.VersionedPayment;
import java.time.Clock;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Cancels Payment and Saga atomically before any balance reservation command is issued. */
@Service
public final class CancelPaymentHandler {

    private final PaymentWorkflowStore payments;
    private final PaymentSagaStore sagas;
    private final IdempotencyStore idempotency;
    private final OutboxAppender outbox;
    private final Clock clock;
    private final TransactionTemplate transactions;

    public CancelPaymentHandler(
            PaymentWorkflowStore payments,
            PaymentSagaStore sagas,
            IdempotencyStore idempotency,
            OutboxAppender outbox,
            Clock clock,
            TransactionTemplate transactions) {
        this.payments = payments;
        this.sagas = sagas;
        this.idempotency = idempotency;
        this.outbox = outbox;
        this.clock = clock;
        this.transactions = transactions;
    }

    public CancelPaymentResult handle(CancelPaymentCommand command) {
        String scope = IdempotencyScope.cancelPayment(command.merchantId());
        String fingerprint = RequestFingerprint.of(command);
        var stored = idempotency.find(scope, command.idempotencyKey());
        if (stored.isPresent()) {
            return replay(stored.get(), fingerprint, scope, command.idempotencyKey());
        }
        try {
            return transactions.execute(status -> cancel(command, scope, fingerprint));
        } catch (ConcurrentIdempotentRequestException lostRace) {
            return idempotency.find(scope, command.idempotencyKey())
                    .map(winner -> replay(winner, fingerprint, scope, command.idempotencyKey()))
                    .orElseThrow(() -> lostRace);
        }
    }

    private CancelPaymentResult cancel(
            CancelPaymentCommand command, String scope, String fingerprint) {
        VersionedPayment storedPayment = payments
                .findForCancellation(command.paymentId(), command.merchantId())
                .orElseThrow(() -> new PaymentNotFoundException(
                        command.paymentId(), command.merchantId()));

        var winner = idempotency.find(scope, command.idempotencyKey());
        if (winner.isPresent()) {
            return replay(winner.get(), fingerprint, scope, command.idempotencyKey());
        }

        var storedSaga = sagas.findByPaymentIdForCancellation(command.paymentId())
                .orElseThrow(() -> new IllegalStateException("Payment Saga is missing"));
        Instant now = clock.instant();
        storedPayment.payment().cancelBeforeReservation(now);
        storedSaga.saga().cancelBeforeReservation(now);
        PaymentAcceptance response = PaymentAcceptance.of(storedPayment.payment());

        idempotency.record(
                scope,
                command.idempotencyKey(),
                new IdempotentResponse(
                        fingerprint,
                        command.paymentId(),
                        CancelPaymentResult.Cancelled.STATUS,
                        response),
                now.plus(CreatePaymentHandler.REPLAY_WINDOW));
        payments.updateWorkflow(storedPayment);
        sagas.update(storedSaga);
        outbox.append(
                PaymentEvents.PAYMENT_CANCELLED,
                PayFlowTopics.PAYMENT_EVENTS,
                command.paymentId().toString(),
                now,
                new PaymentCancelledData(
                        command.paymentId(), command.merchantId(), command.actorId(), now));
        return new CancelPaymentResult.Cancelled(response);
    }

    private static CancelPaymentResult replay(
            IdempotentResponse stored,
            String fingerprint,
            String scope,
            String idempotencyKey) {
        if (!stored.matches(fingerprint)) {
            throw new IdempotencyConflictException(scope, idempotencyKey);
        }
        return new CancelPaymentResult.Replayed(stored.body(), stored.responseStatus());
    }
}
