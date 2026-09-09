package com.payflow.settlement.application;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.EnumMap;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public final class SettlementMetrics {

    private final Map<SettlementProcessingResult, Counter> outcomes =
            new EnumMap<>(SettlementProcessingResult.class);
    private final Counter batchesCompleted;
    private final Counter reconciliationIssues;

    public SettlementMetrics(MeterRegistry registry) {
        for (SettlementProcessingResult result : SettlementProcessingResult.values()) {
            outcomes.put(result, Counter.builder("payflow.settlement.event.outcomes")
                    .tag("result", result.name().toLowerCase())
                    .register(registry));
        }
        batchesCompleted = Counter.builder("payflow.settlement.batches.completed").register(registry);
        reconciliationIssues = Counter.builder("payflow.reconciliation.issues.detected").register(registry);
    }

    public void event(SettlementProcessingResult result) {
        outcomes.get(result).increment();
    }

    public void batchCompleted() {
        batchesCompleted.increment();
    }

    public void reconciliationIssues(int count) {
        reconciliationIssues.increment(count);
    }
}
