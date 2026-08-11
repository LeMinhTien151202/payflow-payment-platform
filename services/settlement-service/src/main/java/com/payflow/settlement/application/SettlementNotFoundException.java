package com.payflow.settlement.application;

import java.util.UUID;

public final class SettlementNotFoundException extends RuntimeException {
    public SettlementNotFoundException(UUID id) {
        super("Settlement batch was not found: " + id);
    }
}
