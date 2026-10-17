package dev.leru.chzzk.api;

import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Public Fabric events for CHZZK donations. */
public final class ChzzkDonationEvents {
    private static final Logger LOGGER = LoggerFactory.getLogger("chzzk_donation_api/events");

    private ChzzkDonationEvents() {
    }

    /**
     * Real upstream donations only. Synthetic command tests are never emitted here.
     *
     * <p>The mod records the upstream event in its per-world dedup store before this callback is
     * delivered. This aims for at-most-once delivery across restarts, but it is not an exactly-once
     * ledger; a crash after recording and before consumer effects can lose a callback. Consumers
     * that grant durable rewards should persist their own idempotency keyed by bindingId + eventId.</p>
     */
    public static final Event<Listener> RECEIVED = EventFactory.createArrayBacked(
            Listener.class,
            listeners -> (server, donation) -> invokeSafely("RECEIVED", listeners, server, donation)
    );

    /** Synthetic operator-triggered tests only. Real donations are never emitted here. */
    public static final Event<Listener> TEST = EventFactory.createArrayBacked(
            Listener.class,
            listeners -> (server, donation) -> invokeSafely("TEST", listeners, server, donation)
    );

    private static void invokeSafely(String eventName, Listener[] listeners, MinecraftServer server, Donation donation) {
        for (Listener listener : listeners) {
            try {
                listener.onDonation(server, donation);
            } catch (RuntimeException e) {
                LOGGER.warn("CHZZK donation {} listener failed for binding '{}' event '{}'",
                        eventName, donation.bindingId(), donation.eventId(), e);
            }
        }
    }

    @FunctionalInterface
    public interface Listener {
        void onDonation(MinecraftServer server, Donation donation);
    }
}
