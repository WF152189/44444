package com.example.aurorarouting.mode;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import org.springframework.transaction.TransactionExecution;
import org.springframework.transaction.TransactionExecutionListener;
import org.springframework.stereotype.Component;

/** 统计由Spring事务管理器创建的最外层可写事务，不需要业务自定义注解。 */
@Component
public class WriteTransactionExecutionListener implements TransactionExecutionListener {

    private final DbModeCoordinator coordinator;
    private final Set<TransactionExecution> admitted =
            Collections.synchronizedSet(Collections.newSetFromMap(new IdentityHashMap<>()));

    public WriteTransactionExecutionListener(DbModeCoordinator coordinator) {
        this.coordinator = coordinator;
    }

    @Override
    public void beforeBegin(TransactionExecution transaction) {
        if (mustTrack(transaction)) {
            coordinator.beginWriteTransaction();
            admitted.add(transaction);
        }
    }

    @Override
    public void afterBegin(TransactionExecution transaction, Throwable beginFailure) {
        if (beginFailure != null) {
            finishOnce(transaction);
        }
    }

    @Override
    public void afterCommit(TransactionExecution transaction, Throwable commitFailure) {
        finishOnce(transaction);
    }

    @Override
    public void afterRollback(TransactionExecution transaction, Throwable rollbackFailure) {
        finishOnce(transaction);
    }

    private boolean mustTrack(TransactionExecution transaction) {
        return transaction.hasTransaction()
                && transaction.isNewTransaction()
                && !transaction.isReadOnly();
    }

    private void finishOnce(TransactionExecution transaction) {
        if (admitted.remove(transaction)) {
            coordinator.endWriteTransaction();
        }
    }
}
