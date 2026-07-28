package com.payflow.payment.domain.model;

/** The next Saga action after applying one risk decision. No network call happens in the domain. */
public enum PaymentRiskAction {
    REQUEST_FUNDS_RESERVATION,
    AWAIT_MANUAL_REVIEW,
    PUBLISH_PAYMENT_FAILED
}
