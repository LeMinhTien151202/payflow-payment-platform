package com.payflow.accountledger.account.application.port;

import com.payflow.accountledger.account.domain.model.Account;
import com.payflow.accountledger.account.domain.model.RefundCredit;
import java.util.Optional;
import java.util.UUID;

public interface AccountRefundStore {
    Optional<Account> findAccountForUpdate(UUID accountId);

    Optional<RefundCredit> findCreditByRefundId(UUID refundId);

    void updateAccount(Account account);

    void saveCredit(RefundCredit credit);
}
