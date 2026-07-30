package com.payflow.risk.application.port;

import java.util.Optional;
import java.util.UUID;

public interface RiskAssessmentStore {
    Optional<RiskAssessmentRecord> findByPaymentId(UUID paymentId);

    boolean saveIfAbsent(RiskAssessmentRecord assessment);
}
