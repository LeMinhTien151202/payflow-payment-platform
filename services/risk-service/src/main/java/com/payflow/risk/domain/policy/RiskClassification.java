package com.payflow.risk.domain.policy;

import com.payflow.risk.domain.model.RiskDecision;
import com.payflow.risk.domain.model.RiskLevel;

public record RiskClassification(RiskLevel level, RiskDecision decision) {}
