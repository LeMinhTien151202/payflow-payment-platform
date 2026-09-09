package com.payflow.settlement.application;

import java.time.LocalDate;
import java.util.UUID;

public record ReconciliationRunResult(
        UUID runId,
        LocalDate settlementDate,
        int checkedItems,
        int openIssueCount,
        int resolvedIssueCount) {
}
