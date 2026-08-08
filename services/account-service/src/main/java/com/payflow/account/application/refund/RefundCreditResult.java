package com.payflow.account.application.refund;

import com.payflow.account.domain.model.RefundCredit;
import com.payflow.events.account.AccountRefundCreditedData;

/** Result to persist with Account and outbox in one future local transaction. */
public record RefundCreditResult(
        RefundCredit credit,
        AccountRefundCreditedData eventData,
        boolean duplicate) {
}
