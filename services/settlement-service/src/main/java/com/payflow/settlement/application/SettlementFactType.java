package com.payflow.settlement.application;

public enum SettlementFactType {
    PAYMENT_SUCCEEDED,
    REFUND_SUCCEEDED,
    LEDGER_PAYMENT_POSTED,
    LEDGER_REFUND_POSTED,
    ACCOUNT_FUNDS_CAPTURED,
    ACCOUNT_REFUND_CREDITED
}
