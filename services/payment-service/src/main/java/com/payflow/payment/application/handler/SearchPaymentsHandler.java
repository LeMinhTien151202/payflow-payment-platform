package com.payflow.payment.application.handler;

import com.payflow.payment.application.PaymentDetail;
import com.payflow.payment.application.PaymentSearchQuery;
import com.payflow.payment.application.PaymentSearchResult;
import com.payflow.payment.application.port.PaymentSearchPage;
import com.payflow.payment.application.port.PaymentSearchPort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Searches only inside the merchant identity already established by authentication. */
@Service
public class SearchPaymentsHandler {

    private final PaymentSearchPort payments;

    public SearchPaymentsHandler(PaymentSearchPort payments) {
        this.payments = payments;
    }

    @Transactional(readOnly = true)
    public PaymentSearchResult handle(PaymentSearchQuery query) {
        PaymentSearchPage result = payments.search(query);
        return PaymentSearchResult.of(
                result.payments().stream().map(PaymentDetail::of).toList(),
                query.page(),
                query.size(),
                result.totalElements());
    }
}
