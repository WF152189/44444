package com.example.aurorarouting.mode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class DbModeCoordinatorTest {

    @Test
    void waitsForExistingWriteBeforeSwitchingToReader() throws Exception {
        DbModeCoordinator coordinator = new DbModeCoordinator();
        coordinator.applyDesiredMode(DesiredMode.READ_WRITE, Duration.ofSeconds(1), () -> true);
        coordinator.beginWriteTransaction();

        CountDownLatch finished = new CountDownLatch(1);
        Thread switcher = new Thread(() -> {
            coordinator.applyDesiredMode(DesiredMode.READ_ONLY, Duration.ofSeconds(2), () -> true);
            finished.countDown();
        });
        switcher.start();

        awaitState(coordinator, RuntimeState.DRAINING);
        assertThat(finished.await(100, TimeUnit.MILLISECONDS)).isFalse();

        coordinator.endWriteTransaction();
        assertThat(finished.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(coordinator.snapshot().actualRoute()).isEqualTo(DbRoute.READER);
        assertThat(coordinator.snapshot().state()).isEqualTo(RuntimeState.READY);
    }

    @Test
    void rejectsNewWriteWhileDraining() throws Exception {
        DbModeCoordinator coordinator = new DbModeCoordinator();
        coordinator.applyDesiredMode(DesiredMode.READ_WRITE, Duration.ofSeconds(1), () -> true);
        coordinator.beginWriteTransaction();

        Thread switcher = new Thread(() ->
                coordinator.applyDesiredMode(DesiredMode.READ_ONLY, Duration.ofSeconds(2), () -> true));
        switcher.start();
        awaitState(coordinator, RuntimeState.DRAINING);

        assertThatThrownBy(coordinator::beginWriteTransaction)
                .isInstanceOf(MaintenanceModeException.class);

        coordinator.endWriteTransaction();
        switcher.join(1000);
    }

    @Test
    void keepsReaderWhenWriterHealthCheckFails() {
        DbModeCoordinator coordinator = new DbModeCoordinator();

        coordinator.applyDesiredMode(DesiredMode.READ_WRITE, Duration.ofSeconds(1), () -> false);

        assertThat(coordinator.snapshot().actualRoute()).isEqualTo(DbRoute.READER);
        assertThat(coordinator.snapshot().state()).isEqualTo(RuntimeState.ERROR);
    }

    private static void awaitState(DbModeCoordinator coordinator, RuntimeState expected)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (coordinator.snapshot().state() != expected && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertThat(coordinator.snapshot().state()).isEqualTo(expected);
    }
}
