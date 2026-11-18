package com.example.aurorarouting.mode;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 单实例用的进程内协调器。
 * admissionGate保证“禁止新写事务”和“登记正在运行的写事务”之间没有竞态。
 */
@Component
public class DbModeCoordinator {

    private static final Logger log = LoggerFactory.getLogger(DbModeCoordinator.class);

    private final ReentrantReadWriteLock admissionGate = new ReentrantReadWriteLock(true);
    private final Object drainMonitor = new Object();
    private final AtomicInteger activeWrites = new AtomicInteger();
    private final AtomicReference<DesiredMode> desiredMode =
            new AtomicReference<>(DesiredMode.READ_ONLY);
    private final AtomicReference<DbRoute> actualRoute =
            new AtomicReference<>(DbRoute.READER);
    private final AtomicReference<RuntimeState> state =
            new AtomicReference<>(RuntimeState.READY);
    private final AtomicReference<String> lastError = new AtomicReference<>("");

    /** 在真正开始一个新的可写事务前调用。 */
    public void beginWriteTransaction() {
        var lock = admissionGate.readLock();
        lock.lock();
        try {
            if (desiredMode.get() != DesiredMode.READ_WRITE
                    || actualRoute.get() != DbRoute.WRITER
                    || state.get() != RuntimeState.READY) {
                throw new MaintenanceModeException("DB is in read-only maintenance mode");
            }
            activeWrites.incrementAndGet();
        } finally {
            lock.unlock();
        }
    }

    /** begin失败、commit或rollback后都必须且只能调用一次。 */
    public void endWriteTransaction() {
        int remaining = activeWrites.decrementAndGet();
        if (remaining < 0) {
            activeWrites.incrementAndGet();
            throw new IllegalStateException("active write transaction counter became negative");
        }
        if (remaining == 0) {
            synchronized (drainMonitor) {
                drainMonitor.notifyAll();
            }
        }
    }

    public synchronized void applyDesiredMode(
            DesiredMode requested,
            Duration drainTimeout,
            BooleanSupplier writerHealthCheck) {

        Objects.requireNonNull(requested);
        if (requested == DesiredMode.READ_ONLY) {
            switchToReadOnly(drainTimeout);
        } else {
            switchToReadWrite(writerHealthCheck);
        }
    }

    private void switchToReadOnly(Duration timeout) {
        if (desiredMode.get() == DesiredMode.READ_ONLY
                && actualRoute.get() == DbRoute.READER
                && state.get() == RuntimeState.READY) {
            return;
        }

        // 写锁会等待所有正在执行beginWriteTransaction()的线程离开，并阻止新线程进入。
        var lock = admissionGate.writeLock();
        lock.lock();
        try {
            desiredMode.set(DesiredMode.READ_ONLY);
            state.set(RuntimeState.DRAINING);
            lastError.set("");
        } finally {
            lock.unlock();
        }

        if (!awaitNoActiveWrites(timeout)) {
            String message = "Timed out waiting for active write transactions: " + activeWrites.get();
            lastError.set(message);
            state.set(RuntimeState.ERROR);
            // 安全优先：不切回Writer，也不对外宣告READY。
            log.error(message);
            return;
        }

        state.set(RuntimeState.SWITCHING);
        actualRoute.set(DbRoute.READER);
        state.set(RuntimeState.READY);
        log.info("Database route switched to READER");
    }

    private void switchToReadWrite(BooleanSupplier writerHealthCheck) {
        if (desiredMode.get() == DesiredMode.READ_WRITE
                && actualRoute.get() == DbRoute.WRITER
                && state.get() == RuntimeState.READY) {
            return;
        }

        state.set(RuntimeState.SWITCHING);
        if (!writerHealthCheck.getAsBoolean()) {
            String message = "Writer health check failed";
            lastError.set(message);
            desiredMode.set(DesiredMode.READ_ONLY);
            actualRoute.set(DbRoute.READER);
            state.set(RuntimeState.ERROR);
            log.error(message);
            return;
        }

        var lock = admissionGate.writeLock();
        lock.lock();
        try {
            actualRoute.set(DbRoute.WRITER);
            desiredMode.set(DesiredMode.READ_WRITE);
            lastError.set("");
            state.set(RuntimeState.READY);
        } finally {
            lock.unlock();
        }
        log.info("Database route switched to WRITER");
    }

    private boolean awaitNoActiveWrites(Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        synchronized (drainMonitor) {
            while (activeWrites.get() > 0) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    return false;
                }
                try {
                    TimeUnit.NANOSECONDS.timedWait(drainMonitor, remaining);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        return true;
    }

    public DbRoute currentRoute() {
        return actualRoute.get();
    }

    public DbModeSnapshot snapshot() {
        return new DbModeSnapshot(
                desiredMode.get(), actualRoute.get(), state.get(),
                activeWrites.get(), lastError.get());
    }
}
