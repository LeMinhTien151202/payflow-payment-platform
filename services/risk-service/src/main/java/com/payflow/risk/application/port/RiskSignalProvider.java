package com.payflow.risk.application.port;

import com.payflow.events.payment.PaymentCreatedData;

/** External signal boundary. It must be called before the local PostgreSQL transaction starts. */
public interface RiskSignalProvider {
    RiskSignalSnapshot collect(PaymentCreatedData payment);
}
