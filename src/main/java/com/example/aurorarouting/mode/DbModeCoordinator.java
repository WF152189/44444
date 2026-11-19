package com.example.aurorarouting.mode;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

/**
 * 单实例应用的数据库路由状态机。
 *
 * <p>进入维护模式时立即把新连接切到Reader。固定等待时间只用于给已经取得的
 * Writer连接留出结束时间，并不等价于“活动写事务数为0”。</p>
 */
@Component
public class DbModeCoordinator {

    private static final Logger log = LoggerFactory.getLogger(DbModeCoordinator.class);

    private final TaskScheduler scheduler;
    private final AtomicReference<DesiredMode> desiredMode =
            new AtomicReference<>(DesiredMode.READ_ONLY);
    private final AtomicReference<DbRoute> actualRoute =
            new AtomicReference<>(DbRoute.READER);
    private final AtomicReference<RuntimeState> state =
            new AtomicReference<>(RuntimeState.STARTING);
    private final AtomicReference<String> lastError = new AtomicReference<>("");
    private final AtomicLong switchVersion = new AtomicLong();

    public DbModeCoordinator(TaskScheduler scheduler) {
        this.scheduler = scheduler;
    }

    public synchronized void applyDesiredMode(
            DesiredMode requested,
            Duration gracePeriod,
            BooleanSupplier writerHealthCheck) {

        Objects.requireNonNull(requested, "requested");
        Objects.requireNonNull(gracePeriod, "gracePeriod");
        Objects.requireNonNull(writerHealthCheck, "writerHealthCheck");

        if (requested == DesiredMode.READ_ONLY) {
            switchToReadOnly(gracePeriod);
        } else {
            switchToReadWrite(writerHealthCheck);
        }
    }

    private void switchToReadOnly(Duration gracePeriod) {
        RuntimeState currentState = state.get();
        if (desiredMode.get() == DesiredMode.READ_ONLY
                && actualRoute.get() == DbRoute.READER
                && (currentState == RuntimeState.READ_ONLY_WAITING
                    || currentState == RuntimeState.READ_ONLY_READY)) {
            return;
        }

        long version = switchVersion.incrementAndGet();
        desiredMode.set(DesiredMode.READ_ONLY);
        actualRoute.set(DbRoute.READER);
        state.set(RuntimeState.READ_ONLY_WAITING);
        lastError.set("");

        log.info("READ_ONLY_WAITING desiredMode=READ_ONLY actualRoute=READER "
                + "gracePeriod={} switchVersion={}", gracePeriod, version);

        scheduler.schedule(
                () -> completeReadOnlyTransition(version),
                Instant.now().plus(gracePeriod));
    }

    private synchronized void completeReadOnlyTransition(long expectedVersion) {
        if (switchVersion.get() != expectedVersion
                || desiredMode.get() != DesiredMode.READ_ONLY
                || actualRoute.get() != DbRoute.READER) {
            log.info("Cancelled stale READ_ONLY transition switchVersion={}", expectedVersion);
            return;
        }

        state.set(RuntimeState.READ_ONLY_READY);
        log.info("DB_MAINTENANCE_READY desiredMode=READ_ONLY actualRoute=READER "
                + "state=READ_ONLY_READY switchVersion={}", expectedVersion);
    }

    private void switchToReadWrite(BooleanSupplier writerHealthCheck) {
        if (desiredMode.get() == DesiredMode.READ_WRITE
                && actualRoute.get() == DbRoute.WRITER
                && state.get() == RuntimeState.READ_WRITE_READY) {
            return;
        }

        long version = switchVersion.incrementAndGet();
        state.set(RuntimeState.SWITCHING);

        if (!writerHealthCheck.getAsBoolean()) {
            String message = "Writer health check failed";
            desiredMode.set(DesiredMode.READ_ONLY);
            actualRoute.set(DbRoute.READER);
            state.set(RuntimeState.ERROR);
            lastError.set(message);
            log.error("{} switchVersion={}", message, version);
            return;
        }

        actualRoute.set(DbRoute.WRITER);
        desiredMode.set(DesiredMode.READ_WRITE);
        state.set(RuntimeState.READ_WRITE_READY);
        lastError.set("");
        log.info("READ_WRITE_READY desiredMode=READ_WRITE actualRoute=WRITER "
                + "state=READ_WRITE_READY switchVersion={}", version);
    }

    public DbRoute currentRoute() {
        return actualRoute.get();
    }

    public DbModeSnapshot snapshot() {
        return new DbModeSnapshot(
                desiredMode.get(), actualRoute.get(), state.get(),
                switchVersion.get(), lastError.get());
    }
}
