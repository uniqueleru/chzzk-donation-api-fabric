package dev.leru.chzzk.fabric;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

final class PlayerEpochTracker<T> {
    private final Function<T, UUID> uuidExtractor;
    private final Map<UUID, PlayerSession<T>> trackedSessions = new HashMap<>();
    private final AtomicLong nextEpoch = new AtomicLong(1);
    private volatile Map<UUID, Long> snapshot = Map.of();

    PlayerEpochTracker(Function<T, UUID> uuidExtractor) {
        this.uuidExtractor = uuidExtractor;
    }

    synchronized Map<UUID, Long> refresh(List<T> players) {
        IdentityHashMap<T, Boolean> seenIdentities = new IdentityHashMap<>();
        Map<UUID, Long> nextSnapshot = new HashMap<>();
        for (T player : players) {
            UUID uuid = uuidExtractor.apply(player);
            seenIdentities.put(player, Boolean.TRUE);
            PlayerSession<T> previous = trackedSessions.get(uuid);
            if (previous == null || previous.player != player) {
                previous = new PlayerSession<>(player, nextEpoch.getAndIncrement());
                trackedSessions.put(uuid, previous);
            }
            nextSnapshot.put(uuid, previous.epoch);
        }
        trackedSessions.entrySet().removeIf(entry -> !seenIdentities.containsKey(entry.getValue().player));
        snapshot = Map.copyOf(nextSnapshot);
        return snapshot;
    }

    Map<UUID, Long> snapshot() {
        return snapshot;
    }

    boolean isCurrent(UUID uuid, long epoch) {
        Long currentEpoch = snapshot.get(uuid);
        return currentEpoch != null && currentEpoch == epoch;
    }

    private record PlayerSession<T>(T player, long epoch) {
    }
}
