package com.payflow.payment.application.port;

import com.payflow.payment.application.PaymentSearchQuery;

/** Read port for bounded, merchant-scoped payment search. */
public interface PaymentSearchPort {

    PaymentSearchPage search(PaymentSearchQuery query);
}
