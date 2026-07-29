package com.payflow.payment.application.port;

import com.payflow.payment.domain.model.Refund;

/** Local persistence boundary for the Refund aggregate. */
public interface RefundRepository {

    void save(Refund refund);
}
