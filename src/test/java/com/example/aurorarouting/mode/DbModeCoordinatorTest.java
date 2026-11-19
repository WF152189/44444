package com.example.aurorarouting.mode;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

class DbModeCoordinatorTest {

    private ThreadPoolTaskScheduler scheduler;
    private DbModeCoordinator coordinator;

    @BeforeEach
    void setUp() {
        scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("db-mode-test-");
        scheduler.initialize();
        coordinator = new DbModeCoordinator(scheduler);
    }

    @AfterEach
    void tearDown() {
        scheduler.shutdown();
    }

    @Test
    void switchesNewConnectionsToReaderImmediatelyAndBecomesReadyAfterGracePeriod()
            throws Exception {
        coordinator.applyDesiredMode(
                DesiredMode.READ_WRITE, Duration.ZERO, () -> true);

        coordinator.applyDesiredMode(
                DesiredMode.READ_ONLY, Duration.ofMillis(30), () -> true);

        assertThat(coordinator.snapshot().actualRoute()).isEqualTo(DbRoute.READER);
        assertThat(coordinator.snapshot().state()).isEqualTo(RuntimeState.READ_ONLY_WAITING);

        awaitState(RuntimeState.READ_ONLY_READY);
        assertThat(coordinator.snapshot().desiredMode()).isEqualTo(DesiredMode.READ_ONLY);
    }

    @Test
    void cancelsOldDelayedTransitionWhenFlagReturnsToReadWrite() throws Exception {
        coordinator.applyDesiredMode(
                DesiredMode.READ_WRITE, Duration.ZERO, () -> true);
        coordinator.applyDesiredMode(
                DesiredMode.READ_ONLY, Duration.ofMillis(30), () -> true);
        coordinator.applyDesiredMode(
                DesiredMode.READ_WRITE, Duration.ZERO, () -> true);

        TimeUnit.MILLISECONDS.sleep(60);

        assertThat(coordinator.snapshot().actualRoute()).isEqualTo(DbRoute.WRITER);
        assertThat(coordinator.snapshot().state()).isEqualTo(RuntimeState.READ_WRITE_READY);
    }

    @Test
    void keepsReaderWhenWriterHealthCheckFails() {
        coordinator.applyDesiredMode(
                DesiredMode.READ_WRITE, Duration.ZERO, () -> false);

        assertThat(coordinator.snapshot().actualRoute()).isEqualTo(DbRoute.READER);
        assertThat(coordinator.snapshot().state()).isEqualTo(RuntimeState.ERROR);
        assertThat(coordinator.snapshot().lastError()).isNotBlank();
    }

    @Test
    void firstReadOnlyPollStartsGracePeriodEvenThoughStartupRouteIsReader()
            throws Exception {
        coordinator.applyDesiredMode(
                DesiredMode.READ_ONLY, Duration.ofMillis(20), () -> true);

        assertThat(coordinator.snapshot().state()).isEqualTo(RuntimeState.READ_ONLY_WAITING);
        awaitState(RuntimeState.READ_ONLY_READY);
    }

    private void awaitState(RuntimeState expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (coordinator.snapshot().state() != expected && System.nanoTime() < deadline) {
            TimeUnit.MILLISECONDS.sleep(5);
        }
        assertThat(coordinator.snapshot().state()).isEqualTo(expected);
    }
}
