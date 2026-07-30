package com.payflow.risk.application.handler;

import com.payflow.events.EventEnvelope;
import com.payflow.events.PayFlowTopics;
import com.payflow.events.payment.PaymentCreatedData;
import com.payflow.events.payment.PaymentEvents;
import com.payflow.risk.application.event.RiskAssessmentEventFactory;
import com.payflow.risk.application.inbox.EventProcessingResult;
import com.payflow.risk.application.inbox.IncomingEventIdentity;
import com.payflow.risk.application.port.OutboxAppender;
import com.payflow.risk.application.port.ProcessedEventStore;
import com.payflow.risk.application.port.RiskAssessmentRecord;
import com.payflow.risk.application.port.RiskAssessmentStore;
import com.payflow.risk.application.port.RiskSignalProvider;
import com.payflow.risk.application.port.RiskSignalSnapshot;
import com.payflow.risk.domain.exception.RiskInvariantViolationException;
import com.payflow.risk.domain.model.RiskAssessment;
import com.payflow.risk.domain.model.RiskEvaluationContext;
import com.payflow.risk.domain.policy.RiskRuleEngine;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Consumes one payment.created using Redis before, and inbox + assessment + outbox inside, SQL tx. */
@Service
public class HandlePaymentCreatedHandler {

    public static final String CONSUMER_NAME = "risk-payment-created-v1";

    private final RiskSignalProvider signals;
    private final ProcessedEventStore inbox;
    private final RiskAssessmentStore assessments;
    private final OutboxAppender outbox;
    private final RiskRuleEngine engine;
    private final RiskAssessmentEventFactory events;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public HandlePaymentCreatedHandler(
            RiskSignalProvider signals,
            ProcessedEventStore inbox,
            RiskAssessmentStore assessments,
            OutboxAppender outbox,
            RiskRuleEngine engine,
            RiskAssessmentEventFactory events,
            TransactionTemplate transactions,
            Clock clock) {
        this.signals = signals;
        this.inbox = inbox;
        this.assessments = assessments;
        this.outbox = outbox;
        this.engine = engine;
        this.events = events;
        this.transactions = transactions;
        this.clock = clock;
    }

    public EventProcessingResult handle(EventEnvelope<PaymentCreatedData> event) {
        requireContract(event);
        RiskSignalSnapshot snapshot = signals.collect(event.data());
        EventProcessingResult result = transactions.execute(status -> persist(event, snapshot));
        return Objects.requireNonNull(result, "transaction returned no result");
    }

    private EventProcessingResult persist(
            EventEnvelope<PaymentCreatedData> event, RiskSignalSnapshot snapshot) {
        Instant assessedAt = clock.instant();
        boolean firstDelivery = inbox.recordIfNew(new IncomingEventIdentity(
                event.eventId(),
                CONSUMER_NAME,
                event.eventType(),
                event.aggregateId(),
                assessedAt));
        if (!firstDelivery) {
            return EventProcessingResult.DUPLICATE;
        }

        var existing = assessments.findByPaymentId(event.data().paymentId());
        if (existing.isPresent()) {
            requireSameIntent(existing.orElseThrow().payment(), event.data());
            return EventProcessingResult.BUSINESS_DUPLICATE;
        }

        RiskEvaluationContext context = new RiskEvaluationContext(
                event.data().paymentId(),
                event.data().customerId(),
                event.data().merchantId(),
                event.data().amount(),
                event.data().currency(),
                snapshot.paymentCountLastMinute(),
                snapshot.totalAmountLastHour(),
                snapshot.newDevice(),
                snapshot.failedPaymentsLastTenMinutes(),
                snapshot.merchantSuspicious(),
                snapshot.ipCountryChanged());
        RiskAssessment assessment = engine.evaluate(context);
        boolean inserted = assessments.saveIfAbsent(new RiskAssessmentRecord(
                UUID.randomUUID(), event.data(), snapshot, assessment, assessedAt));
        if (!inserted) {
            RiskAssessmentRecord concurrent = assessments
                    .findByPaymentId(event.data().paymentId())
                    .orElseThrow(() -> new IllegalStateException(
                            "assessment conflict disappeared inside transaction"));
            requireSameIntent(concurrent.payment(), event.data());
            return EventProcessingResult.BUSINESS_DUPLICATE;
        }
        outbox.append(
                PayFlowTopics.RISK_EVENTS,
                events.completed(UUID.randomUUID(), event, assessment, assessedAt));
        return EventProcessingResult.PROCESSED;
    }

    private static void requireContract(EventEnvelope<PaymentCreatedData> event) {
        Objects.requireNonNull(event, "event");
        if (!PaymentEvents.PAYMENT_CREATED.name().equals(event.eventType())
                || PaymentEvents.PAYMENT_CREATED.version() != event.eventVersion()
                || !PaymentEvents.AGGREGATE_TYPE.equals(event.aggregateType())) {
            throw new RiskInvariantViolationException("risk consumer requires payment.created v1");
        }
        if (!event.data().paymentId().toString().equals(event.aggregateId())) {
            throw new RiskInvariantViolationException(
                    "payment.created aggregateId does not match payload paymentId");
        }
    }

    private static void requireSameIntent(PaymentCreatedData stored, PaymentCreatedData incoming) {
        boolean same = stored.paymentId().equals(incoming.paymentId())
                && stored.merchantId().equals(incoming.merchantId())
                && stored.customerId().equals(incoming.customerId())
                && stored.sourceAccountId().equals(incoming.sourceAccountId())
                && stored.amount().compareTo(incoming.amount()) == 0
                && stored.currency().equals(incoming.currency())
                && stored.createdAt().equals(incoming.createdAt());
        if (!same) {
            throw new RiskInvariantViolationException(
                    "paymentId already has a risk assessment for a different intent");
        }
    }
}
