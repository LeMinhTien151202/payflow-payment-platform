package com.payflow.settlement.application;

import com.payflow.settlement.application.port.SettlementStore;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public final class HandleSettlementEventHandler {

    private final SettlementEventParser parser;
    private final SettlementStore store;
    private final SettlementMetrics metrics;
    private final Clock clock;
    private final ZoneId businessZone;
    private final TransactionTemplate transactions;

    public HandleSettlementEventHandler(
            SettlementEventParser parser,
            SettlementStore store,
            SettlementMetrics metrics,
            Clock clock,
            SettlementRuntimeProperties properties,
            TransactionTemplate transactions) {
        this.parser = parser;
        this.store = store;
        this.metrics = metrics;
        this.clock = clock;
        this.businessZone = properties.businessZone();
        this.transactions = transactions;
    }

    public SettlementProcessingResult handle(String kafkaKey, String payload) {
        var parsed = parser.parse(kafkaKey, payload);
        if (parsed.isEmpty()) {
            metrics.event(SettlementProcessingResult.IGNORED);
            return SettlementProcessingResult.IGNORED;
        }
        SettlementFact fact = parsed.orElseThrow();
        var result = Objects.requireNonNull(transactions.execute(status -> process(fact)));
        metrics.event(result);
        return result;
    }

    private SettlementProcessingResult process(SettlementFact fact) {
        var now = clock.instant();
        if (!store.recordInbox(fact, now)) {
            return SettlementProcessingResult.DUPLICATE_EVENT;
        }
        LocalDate businessDate = fact.occurredAt().atZone(businessZone).toLocalDate();
        if (!store.insertFinancialFact(fact, now)) {
            store.recordDuplicateBusinessFact(fact, businessDate, now);
            return SettlementProcessingResult.DUPLICATE_BUSINESS_FACT;
        }
        if (fact.contribution() == null) {
            return SettlementProcessingResult.APPLIED;
        }
        return switch (store.applyContribution(
                fact.eventId(), fact.contribution(), businessDate, now)) {
            case APPLIED -> SettlementProcessingResult.APPLIED;
            case BUSINESS_DUPLICATE -> SettlementProcessingResult.DUPLICATE_BUSINESS_FACT;
            case LATE -> SettlementProcessingResult.LATE_EVENT;
        };
    }
}
