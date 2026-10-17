package dev.leru.chzzk.fabric;

import dev.leru.chzzk.api.ChzzkDonationEvents;
import dev.leru.chzzk.api.Donation;
import dev.leru.chzzk.core.ChzzkClient;
import dev.leru.chzzk.core.RawDonation;
import dev.leru.chzzk.core.SeenEventStore;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.WorldSavePath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class ChzzkDonationService implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger("chzzk_donation_api/service");
    static final int DISPATCH_QUEUE_CAPACITY = 1024;
    static final int IO_QUEUE_CAPACITY = 1024;
    private static final int MAX_DISPATCH_PER_TICK = 256;

    private final MinecraftServer server;
    private final Path configPath;
    private final Path seenStorePath;
    private final ExecutorService ioExecutor;
    private final ArrayBlockingQueue<QueuedDonation> dispatchQueue = new ArrayBlockingQueue<>(DISPATCH_QUEUE_CAPACITY);
    private final AtomicLong generation = new AtomicLong(0);
    private final AtomicLong ioRejected = new AtomicLong();
    private final Map<String, BindingRuntime> runtimes = new ConcurrentHashMap<>();
    private final PlayerEpochTracker<ServerPlayerEntity> playerEpochTracker = new PlayerEpochTracker<>(ServerPlayerEntity::getUuid);

    private volatile Map<UUID, Long> onlinePlayerEpochs = Map.of();
    private volatile ChzzkDonationConfig config = ChzzkDonationConfig.defaults();
    private volatile SeenEventStore seenStore;
    private volatile boolean running;

    ChzzkDonationService(MinecraftServer server, Path configPath) {
        this.server = server;
        this.configPath = configPath;
        this.seenStorePath = server.getSavePath(WorldSavePath.ROOT)
                .resolve("chzzk-donation-api")
                .resolve("seen-events.jsonl");
        this.ioExecutor = boundedIoExecutor(IO_QUEUE_CAPACITY, "chzzk-donation-api-io", ioRejected);
    }

    ChzzkDonationService(MinecraftServer server, Path configPath, Path seenStorePath, ExecutorService ioExecutor) {
        this.server = server;
        this.configPath = configPath;
        this.seenStorePath = seenStorePath;
        this.ioExecutor = ioExecutor;
    }

    void start() {
        running = true;
        try {
            seenStore = new SeenEventStore(seenStorePath);
        } catch (IOException e) {
            LOGGER.warn("CHZZK donation dedup store could not be opened; no donation clients started", e);
            running = false;
            seenStore = null;
            return;
        }
        ChzzkDonationConfig.LoadResult loaded = ChzzkDonationConfig.load(configPath);
        if (loaded.valid()) {
            applyConfig(loaded.config());
            if (loaded.createdDefault()) {
                LOGGER.info("Created default CHZZK donation config with no bindings");
            }
        } else {
            LOGGER.warn("CHZZK donation config invalid; using empty safe defaults: {}", loaded.reason());
            applyConfig(ChzzkDonationConfig.defaults());
        }
    }

    boolean reload() {
        if (!canReload(running, seenStore)) {
            LOGGER.warn("CHZZK donation config reload rejected; service is not running cleanly");
            return false;
        }
        ChzzkDonationConfig.LoadResult loaded = ChzzkDonationConfig.load(configPath);
        if (!loaded.valid()) {
            LOGGER.warn("CHZZK donation config reload rejected; keeping previous config: {}", loaded.reason());
            return false;
        }
        applyConfig(loaded.config());
        return true;
    }

    static boolean canReload(boolean running, SeenEventStore store) {
        return running && store != null;
    }

    void tick() {
        if (!running) {
            return;
        }
        refreshOnlinePlayers();
        drainDispatchQueue();
    }

    private int drainDispatchQueue() {
        long activeGeneration = generation.get();
        int dispatchedThisTick = 0;
        for (int i = 0; i < MAX_DISPATCH_PER_TICK; i++) {
            QueuedDonation queued = dispatchQueue.poll();
            if (queued == null) {
                break;
            }
            if (queued.generation != activeGeneration || !running) {
                continue;
            }
            BindingRuntime runtime = runtime(queued.donation.bindingId());
            if (!isStillOnline(queued.donation.streamerUuid(), queued.playerEpoch)) {
                runtime.offlineDropped.incrementAndGet();
                continue;
            }
            runtime.dispatched.incrementAndGet();
            dispatchedThisTick++;
            ChzzkDonationEvents.RECEIVED.invoker().onDonation(server, queued.donation);
        }
        return dispatchedThisTick;
    }

    void startQaReceivePath(SeenEventStore store, ChzzkDonationConfig nextConfig, Map<UUID, Long> onlineEpochs) {
        running = true;
        seenStore = store;
        config = nextConfig;
        generation.incrementAndGet();
        onlinePlayerEpochs = Map.copyOf(onlineEpochs);
    }

    void receiveQaDonation(ChzzkDonationConfig.Binding binding, RawDonation rawDonation) {
        receiveDonation(generation.get(), binding, rawDonation);
    }

    void setQaOnlineEpochs(Map<UUID, Long> onlineEpochs) {
        onlinePlayerEpochs = Map.copyOf(onlineEpochs);
    }

    int tickQaDispatchWithoutRefreshingPlayers() {
        if (!running) {
            return 0;
        }
        return drainDispatchQueue();
    }

    void dispatchTest(ServerPlayerEntity player, long amount, String message) {
        Donation donation = new Donation(
                "test",
                player.getUuid(),
                "test-" + UUID.randomUUID(),
                "테스트",
                message == null || message.isBlank() ? "CHZZK donation test" : message,
                amount,
                System.currentTimeMillis(),
                true
        );
        ChzzkDonationEvents.TEST.invoker().onDonation(server, donation);
    }

    String statusText() {
        ChzzkDonationConfig snapshot = config;
        StringBuilder builder = new StringBuilder();
        builder.append("CHZZK Donation API: ")
                .append(running ? "running" : "stopped")
                .append(", bindings ")
                .append(snapshot.bindings().size())
                .append('/')
                .append(ChzzkDonationConfig.MAX_BINDINGS)
                .append(", queue ")
                .append(dispatchQueue.size())
                .append('/')
                .append(DISPATCH_QUEUE_CAPACITY)
                .append(", ioRejected ")
                .append(ioRejected.get());
        Map<UUID, Long> online = onlinePlayerEpochs;
        for (ChzzkDonationConfig.Binding binding : snapshot.bindings()) {
            BindingRuntime runtime = runtime(binding.id());
            builder.append('\n')
                    .append("- ").append(binding.id())
                    .append(" enabled=").append(binding.enabled())
                    .append(" online=").append(online.containsKey(binding.playerUuid()))
                    .append(" state=").append(runtime.state)
                    .append(" received=").append(runtime.received.get())
                    .append(" dispatched=").append(runtime.dispatched.get())
                    .append(" duplicate=").append(runtime.duplicates.get())
                    .append(" offlineDropped=").append(runtime.offlineDropped.get())
                    .append(" queueDropped=").append(runtime.queueDropped.get())
                    .append(" errors=").append(runtime.errors.get());
        }
        return builder.toString();
    }

    private synchronized void applyConfig(ChzzkDonationConfig nextConfig) {
        long nextGeneration = generation.incrementAndGet();
        closeClientsNonBlocking();
        dispatchQueue.clear();
        reconcileRuntimes(nextConfig);
        config = nextConfig;
        for (ChzzkDonationConfig.Binding binding : nextConfig.bindings()) {
            BindingRuntime runtime = runtime(binding.id());
            runtime.state = binding.enabled() ? "starting" : "disabled";
            if (!binding.enabled()) {
                continue;
            }
            ChzzkClient client = new ChzzkClient(
                    binding.sourceHash(),
                    donation -> receiveDonation(nextGeneration, binding, donation),
                    status -> {
                        if (nextGeneration == generation.get()) {
                            runtime(binding.id()).state = sanitizeStatus(status);
                        }
                    }
            );
            runtime.client = client;
            try {
                client.start();
                runtime.state = "started";
            } catch (Exception e) {
                runtime.errors.incrementAndGet();
                runtime.state = "start_failed";
                LOGGER.warn("CHZZK donation client failed to start for binding '{}'", binding.id(), e);
            }
        }
    }

    private void receiveDonation(long callbackGeneration, ChzzkDonationConfig.Binding binding, RawDonation rawDonation) {
        if (!running || callbackGeneration != generation.get()) {
            return;
        }
        BindingRuntime runtime = runtime(binding.id());
        runtime.received.incrementAndGet();
        Long playerEpoch = onlinePlayerEpochs.get(binding.playerUuid());
        if (playerEpoch == null) {
            runtime.offlineDropped.incrementAndGet();
            return;
        }
        submitIo(runtime, new IoDonationTask(callbackGeneration, binding, rawDonation, playerEpoch));
    }

    private void submitIo(BindingRuntime runtime, IoDonationTask task) {
        try {
            ioExecutor.execute(task);
        } catch (RejectedExecutionException e) {
            recordIoRejected(runtime);
        }
    }

    private void recordIoRejected(BindingRuntime runtime) {
        ioRejected.incrementAndGet();
        runtime.queueDropped.incrementAndGet();
        runtime.state = "io_queue_full";
    }

    private void handleDonationIo(IoDonationTask task) {
        ChzzkDonationConfig.Binding binding = task.binding;
        BindingRuntime runtime = runtime(binding.id());
        if (!running || task.generation != generation.get() || !isStillOnline(binding.playerUuid(), task.playerEpoch)) {
            runtime.offlineDropped.incrementAndGet();
            return;
        }
        try {
            SeenEventStore store = seenStore;
            if (store == null) {
                runtime.errors.incrementAndGet();
                runtime.state = "store_unavailable";
                return;
            }
            boolean firstSeen = store.markIfNew(binding.sourceHash(), task.rawDonation.eventId(), System.currentTimeMillis());
            if (!firstSeen) {
                runtime.duplicates.incrementAndGet();
                return;
            }
            if (!running || task.generation != generation.get() || !isStillOnline(binding.playerUuid(), task.playerEpoch)) {
                runtime.offlineDropped.incrementAndGet();
                return;
            }
            Donation donation = toPublicDonation(binding, task.rawDonation);
            if (!dispatchQueue.offer(new QueuedDonation(task.generation, task.playerEpoch, donation))) {
                runtime.queueDropped.incrementAndGet();
                runtime.state = "dispatch_queue_full";
            }
        } catch (IOException | RuntimeException e) {
            runtime.errors.incrementAndGet();
            runtime.state = "receive_error";
            LOGGER.warn("CHZZK donation receive path failed for binding '{}'", binding.id(), e);
        }
    }

    private Donation toPublicDonation(ChzzkDonationConfig.Binding binding, RawDonation rawDonation) {
        return new Donation(
                binding.id(),
                binding.playerUuid(),
                rawDonation.eventId(),
                rawDonation.donor(),
                rawDonation.message(),
                rawDonation.amount(),
                rawDonation.timestampMillis() > 0 ? rawDonation.timestampMillis() : Instant.now().toEpochMilli(),
                false
        );
    }

    private void refreshOnlinePlayers() {
        onlinePlayerEpochs = playerEpochTracker.refresh(server.getPlayerManager().getPlayerList());
    }

    private boolean isStillOnline(UUID playerUuid, long playerEpoch) {
        Long currentEpoch = onlinePlayerEpochs.get(playerUuid);
        return currentEpoch != null && currentEpoch == playerEpoch;
    }

    private String sanitizeStatus(String status) {
        if (status == null || status.isBlank()) {
            return "unknown";
        }
        String sanitized = status
                .replaceAll("https?://\\S+", "[redacted-url]")
                .replaceAll("[0-9a-f]{32}", "[redacted-source]");
        return sanitized.substring(0, Math.min(120, sanitized.length()));
    }

    private BindingRuntime runtime(String bindingId) {
        return runtimes.computeIfAbsent(bindingId, ignored -> new BindingRuntime());
    }

    private void reconcileRuntimes(ChzzkDonationConfig nextConfig) {
        Set<String> liveIds = new HashSet<>();
        for (ChzzkDonationConfig.Binding binding : nextConfig.bindings()) {
            liveIds.add(binding.id());
        }
        runtimes.keySet().retainAll(liveIds);
    }

    private void closeClientsNonBlocking() {
        Collection<ChzzkClient> clients = new ArrayList<>();
        for (BindingRuntime runtime : runtimes.values()) {
            ChzzkClient client = runtime.client;
            runtime.client = null;
            if (client != null) {
                clients.add(client);
            }
        }
        if (clients.isEmpty()) {
            return;
        }
        Thread closer = new NamedThreadFactory("chzzk-donation-api-client-close").newThread(() -> {
            for (ChzzkClient client : clients) {
                try {
                    client.close();
                } catch (Exception e) {
                    LOGGER.warn("CHZZK donation client close failed", e);
                }
            }
        });
        closer.start();
    }

    @Override
    public synchronized void close() {
        running = false;
        generation.incrementAndGet();
        closeClientsNonBlocking();
        dispatchQueue.clear();
        ioExecutor.shutdownNow();
        SeenEventStore store = seenStore;
        seenStore = null;
        if (store != null) {
            try {
                store.close();
            } catch (Exception e) {
                LOGGER.warn("CHZZK donation dedup store close failed", e);
            }
        }
    }

    static ThreadPoolExecutor boundedIoExecutor(int queueCapacity, String threadName, AtomicLong rejected) {
        BlockingQueue<Runnable> workQueue = new ArrayBlockingQueue<>(queueCapacity);
        return new ThreadPoolExecutor(
                1,
                1,
                0L,
                TimeUnit.MILLISECONDS,
                workQueue,
                new NamedThreadFactory(threadName),
                (runnable, executor) -> {
                    if (runnable instanceof RejectionAware rejectionAware) {
                        rejectionAware.onRejected();
                    } else {
                        rejected.incrementAndGet();
                    }
                }
        );
    }

    private record QueuedDonation(long generation, long playerEpoch, Donation donation) {
    }

    private final class IoDonationTask implements Runnable, RejectionAware {
        private final long generation;
        private final ChzzkDonationConfig.Binding binding;
        private final RawDonation rawDonation;
        private final long playerEpoch;

        private IoDonationTask(long generation, ChzzkDonationConfig.Binding binding, RawDonation rawDonation, long playerEpoch) {
            this.generation = generation;
            this.binding = binding;
            this.rawDonation = rawDonation;
            this.playerEpoch = playerEpoch;
        }

        @Override
        public void run() {
            handleDonationIo(this);
        }

        @Override
        public void onRejected() {
            recordIoRejected(runtime(binding.id()));
        }
    }

    interface RejectionAware {
        void onRejected();
    }

    static final class BindingRuntime {
        volatile ChzzkClient client;
        volatile String state = "unknown";
        final AtomicLong received = new AtomicLong();
        final AtomicLong dispatched = new AtomicLong();
        final AtomicLong duplicates = new AtomicLong();
        final AtomicLong offlineDropped = new AtomicLong();
        final AtomicLong queueDropped = new AtomicLong();
        final AtomicLong errors = new AtomicLong();
    }

    private static final class NamedThreadFactory implements ThreadFactory {
        private final String name;

        private NamedThreadFactory(String name) {
            this.name = name;
        }

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);
            return thread;
        }
    }
}
