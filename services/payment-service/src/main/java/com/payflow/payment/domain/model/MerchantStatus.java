package com.payflow.payment.domain.model;

/**
 * Merchant lifecycle, as far as payment intake needs to know about it.
 *
 * <p>The values mirror the {@code merchants_status_known} constraint in {@code V2__payment_intake.sql}.
 * Only {@link #ACTIVE} may take money; the other three are distinct because the reason a merchant
 * cannot take money determines who has to do something about it — onboarding, compliance, or nobody.
 */
public enum MerchantStatus {

    /** Onboarding incomplete. Not yet allowed to transact. */
    PENDING,

    /** Fully onboarded and permitted to take payments. */
    ACTIVE,

    /** Temporarily blocked, for example by a compliance hold. Existing payments are unaffected. */
    SUSPENDED,

    /** Permanently closed. */
    CLOSED;

    /**
     * Allow-list, not a deny-list. A status added later is refused until someone decides otherwise,
     * which is the safe direction for a check that stands between a request and someone's money.
     */
    public boolean canAcceptPayments() {
        return this == ACTIVE;
    }
}
