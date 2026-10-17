package dev.leru.chzzk.api;

import java.util.UUID;

/**
 * Immutable public donation payload delivered to Fabric listeners.
 *
 * <p>No CHZZK source hashes, access tokens, URLs, or other local admin secrets are exposed through
 * this API. Consumers should use {@link #bindingId()}, {@link #streamerUuid()}, and
 * {@link #eventId()} for their own idempotency.</p>
 */
public record Donation(
        String bindingId,
        UUID streamerUuid,
        String eventId,
        String donor,
        String message,
        long amount,
        long timestampMillis,
        boolean test
) {
    public Donation {
        if (bindingId == null || bindingId.isBlank()) {
            throw new IllegalArgumentException("bindingId is required");
        }
        if (streamerUuid == null) {
            throw new IllegalArgumentException("streamerUuid is required");
        }
        if (eventId == null || eventId.isBlank()) {
            throw new IllegalArgumentException("eventId is required");
        }
        donor = donor == null ? "" : donor;
        message = message == null ? "" : message;
        if (amount <= 0) {
            throw new IllegalArgumentException("amount must be positive");
        }
    }
}
