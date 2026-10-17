package dev.leru.chzzk.core;

public record RawDonation(String eventId, String donor, String message, long amount, long timestampMillis) {}
