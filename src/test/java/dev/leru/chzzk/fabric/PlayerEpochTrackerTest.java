package dev.leru.chzzk.fabric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PlayerEpochTrackerTest {
    private static final UUID UUID_ONE = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Test
    void publishesImmutableSnapshotWithoutTransientClear() {
        PlayerEpochTracker<PlayerRef> tracker = new PlayerEpochTracker<>(PlayerRef::uuid);
        PlayerRef player = new PlayerRef(UUID_ONE);

        long firstEpoch = tracker.refresh(List.of(player)).get(UUID_ONE);
        assertTrue(tracker.isCurrent(UUID_ONE, firstEpoch));
        assertEquals(firstEpoch, tracker.snapshot().get(UUID_ONE));

        tracker.refresh(List.of(player));
        assertEquals(firstEpoch, tracker.snapshot().get(UUID_ONE));
    }

    @Test
    void reconnectWithSameUuidGetsNewEpochAndInvalidatesOldQueuedEvents() {
        PlayerEpochTracker<PlayerRef> tracker = new PlayerEpochTracker<>(PlayerRef::uuid);
        PlayerRef firstConnection = new PlayerRef(UUID_ONE);
        long firstEpoch = tracker.refresh(List.of(firstConnection)).get(UUID_ONE);

        tracker.refresh(List.of());
        assertFalse(tracker.isCurrent(UUID_ONE, firstEpoch));

        PlayerRef secondConnection = new PlayerRef(UUID_ONE);
        long secondEpoch = tracker.refresh(List.of(secondConnection)).get(UUID_ONE);

        assertFalse(tracker.isCurrent(UUID_ONE, firstEpoch));
        assertTrue(tracker.isCurrent(UUID_ONE, secondEpoch));
    }

    private record PlayerRef(UUID uuid) {
    }
}
