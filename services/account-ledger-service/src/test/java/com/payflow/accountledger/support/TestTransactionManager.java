package com.payflow.accountledger.support;

import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

/** Executes TransactionTemplate callbacks without external infrastructure. */
public final class TestTransactionManager implements PlatformTransactionManager {
    @Override
    public TransactionStatus getTransaction(TransactionDefinition definition) {
        return new SimpleTransactionStatus();
    }

    @Override
    public void commit(TransactionStatus status) {}

    @Override
    public void rollback(TransactionStatus status) {}
}
