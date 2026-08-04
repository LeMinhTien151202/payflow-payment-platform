package com.payflow.payment.application.port;

import com.payflow.payment.domain.model.Payment;
import java.util.List;

/** Persistence result kept independent from the HTTP response shape. */
public record PaymentSearchPage(List<Payment> payments, long totalElements) {

    public PaymentSearchPage {
        payments = List.copyOf(payments);
        if (totalElements < payments.size()) {
            throw new IllegalArgumentException("totalElements cannot be smaller than the page");
        }
    }
}
