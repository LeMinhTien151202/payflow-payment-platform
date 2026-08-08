package com.payflow.ledger.domain.exception;

import java.math.BigDecimal;

public final class UnbalancedJournalException extends JournalInvariantViolationException {

    public UnbalancedJournalException(BigDecimal debits, BigDecimal credits, String currency) {
        super(
                "journal is not balanced for "
                        + currency
                        + ": debits="
                        + debits
                        + ", credits="
                        + credits);
    }
}
