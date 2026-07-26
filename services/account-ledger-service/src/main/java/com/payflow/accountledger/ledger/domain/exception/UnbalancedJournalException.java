package com.payflow.accountledger.ledger.domain.exception;

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
