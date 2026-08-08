package com.payflow.account.application.port;

import com.payflow.account.domain.model.Account;
import com.payflow.account.domain.model.RefundCredit;
import java.util.Optional;
import java.util.UUID;

public interface AccountRefundStore {
    Optional<Account> findAccountForUpdate(UUID accountId);

    Optional<RefundCredit> findCreditByRefundId(UUID refundId);

    void updateAccount(Account account);

    void saveCredit(RefundCredit credit);
}
