package com.lakeon.service;

import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Test helper: creates a TransactionTemplate that executes callbacks directly
 * without an actual transaction (pass-through for unit tests).
 */
class TestTransactionTemplate {
    static TransactionTemplate create() {
        return new TransactionTemplate(new PlatformTransactionManager() {
            @Override
            public TransactionStatus getTransaction(TransactionDefinition definition) throws TransactionException {
                return new SimpleTransactionStatus();
            }
            @Override
            public void commit(TransactionStatus status) throws TransactionException {}
            @Override
            public void rollback(TransactionStatus status) throws TransactionException {}
        });
    }
}
