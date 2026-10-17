package dev.leru.chzzk.fabric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.leru.chzzk.api.ChzzkDonationEvents;
import dev.leru.chzzk.core.RawDonation;
import dev.leru.chzzk.core.SeenEventStore;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ChzzkDonationServiceLifecycleTest {
    private static final UUID PLAYER_UUID = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
    private static final ChzzkDonationConfig.Binding BINDING = new ChzzkDonationConfig.Binding(
            "binding",
            PLAYER_UUID,
            "0123456789abcdef0123456789abcdef",
            true
    );

    @TempDir
    Path tempDir;

    @Test
    void boundedIoExecutorRejectsWithoutThrowingAndCountsOnce() throws Exception {
        AtomicLong rejected = new AtomicLong();
        ThreadPoolExecutor executor = ChzzkDonationService.boundedIoExecutor(1, "test-io", rejected);
        CountDownLatch blockerStarted = new CountDownLatch(1);
        CountDownLatch releaseBlocker = new CountDownLatch(1);
        AtomicInteger awareRejected = new AtomicInteger();

        executor.execute(() -> {
            blockerStarted.countDown();
            try {
                releaseBlocker.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        assertTrue(blockerStarted.await(5, TimeUnit.SECONDS));
        executor.execute(() -> { });
        executor.execute(new RejectionAwareTask(awareRejected));

        assertEquals(1, awareRejected.get());
        assertEquals(0, rejected.get(), "aware service tasks update their binding counters instead of double-counting globally");
        releaseBlocker.countDown();
        executor.shutdownNow();
    }

    @Test
    void canReloadRequiresRunningServiceAndOpenedStore() throws Exception {
        try (SeenEventStore store = new SeenEventStore(tempDir.resolve("seen.jsonl"))) {
            assertFalse(ChzzkDonationService.canReload(false, store));
            assertFalse(ChzzkDonationService.canReload(true, null));
            assertTrue(ChzzkDonationService.canReload(true, store));
        }
    }

    @Test
    void qaSeamRunsReceiveDedupAndDispatchPathWithoutNetwork() throws Exception {
        AtomicInteger received = new AtomicInteger();
        Thread qaMainThread = Thread.currentThread();
        AtomicInteger mainThreadDeliveries = new AtomicInteger();
        ChzzkDonationEvents.RECEIVED.register((server, donation) -> {
            if ("qa-event".equals(donation.eventId())) {
                received.incrementAndGet();
                if (Thread.currentThread() == qaMainThread) {
                    mainThreadDeliveries.incrementAndGet();
                }
            }
        });
        try (SeenEventStore store = new SeenEventStore(tempDir.resolve("seen.jsonl"))) {
            ChzzkDonationService service = new ChzzkDonationService(
                    null,
                    tempDir.resolve("config.json"),
                    tempDir.resolve("seen.jsonl"),
                    new DirectExecutorService()
            );
            service.startQaReceivePath(store, new ChzzkDonationConfig(1, List.of(BINDING)), Map.of(PLAYER_UUID, 1L));

            RawDonation donation = new RawDonation("qa-event", "donor", "msg", 1000, 123L);
            service.receiveQaDonation(BINDING, donation);
            assertEquals(1, service.tickQaDispatchWithoutRefreshingPlayers());
            service.receiveQaDonation(BINDING, donation);
            assertEquals(0, service.tickQaDispatchWithoutRefreshingPlayers());

            assertEquals(1, received.get());
            assertEquals(1, mainThreadDeliveries.get());
            String status = service.statusText();
            assertTrue(status.contains("received=2"));
            assertTrue(status.contains("dispatched=1"));
            assertTrue(status.contains("duplicate=1"));
            service.close();
        }
    }

    @Test
    void qaSeamDropsOfflineAndStaleRejoinEpochs() throws Exception {
        AtomicInteger received = new AtomicInteger();
        ChzzkDonationEvents.RECEIVED.register((server, donation) -> {
            if (donation.eventId().startsWith("offline-test")) {
                received.incrementAndGet();
            }
        });
        try (SeenEventStore store = new SeenEventStore(tempDir.resolve("seen.jsonl"))) {
            ChzzkDonationService service = new ChzzkDonationService(
                    null,
                    tempDir.resolve("config.json"),
                    tempDir.resolve("seen.jsonl"),
                    new DirectExecutorService()
            );
            service.startQaReceivePath(store, new ChzzkDonationConfig(1, List.of(BINDING)), Map.of());
            service.receiveQaDonation(BINDING, new RawDonation("offline-test-drop", "donor", "msg", 1000, 123L));
            assertEquals(0, service.tickQaDispatchWithoutRefreshingPlayers());

            service.setQaOnlineEpochs(Map.of(PLAYER_UUID, 1L));
            service.receiveQaDonation(BINDING, new RawDonation("offline-test-rejoin", "donor", "msg", 1000, 123L));
            service.setQaOnlineEpochs(Map.of(PLAYER_UUID, 2L));
            assertEquals(0, service.tickQaDispatchWithoutRefreshingPlayers());

            assertEquals(0, received.get());
            assertTrue(service.statusText().contains("offlineDropped=2"));
            service.close();
        }
    }

    private record RejectionAwareTask(AtomicInteger rejected) implements Runnable, ChzzkDonationService.RejectionAware {
        @Override
        public void run() {
        }

        @Override
        public void onRejected() {
            rejected.incrementAndGet();
        }
    }

    private static final class DirectExecutorService extends AbstractExecutorService {
        private boolean shutdown;

        @Override
        public void shutdown() {
            shutdown = true;
        }

        @Override
        public List<Runnable> shutdownNow() {
            shutdown = true;
            return List.of();
        }

        @Override
        public boolean isShutdown() {
            return shutdown;
        }

        @Override
        public boolean isTerminated() {
            return shutdown;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return true;
        }

        @Override
        public void execute(Runnable command) {
            if (shutdown) {
                throw new java.util.concurrent.RejectedExecutionException("shutdown");
            }
            command.run();
        }
    }
}
