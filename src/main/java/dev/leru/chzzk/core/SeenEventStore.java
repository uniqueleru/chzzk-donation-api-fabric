package dev.leru.chzzk.core;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class SeenEventStore implements Closeable {
    private static final long MAX_AGE_MILLIS = 7L * 24L * 60L * 60L * 1000L;
    static final int MAX_COUNT = 50_000;
    private static final int MAX_LOAD_LINES = MAX_COUNT * 2;
    private static final long MAX_FILE_BYTES = 16L * 1024L * 1024L;
    private static final String HEADER = "chzzk-seen-v1";

    private final Path file;
    private final Map<Key, Long> seen = new HashMap<>();

    public SeenEventStore(Path file) throws IOException {
        this.file = Objects.requireNonNull(file, "file");
        load();
    }

    public synchronized boolean markIfNew(String sourceHash, String eventId, long nowMillis) throws IOException {
        if (!DonationParser.isValidSourceHash(sourceHash)) {
            throw new IllegalArgumentException("invalid sourceHash");
        }
        if (!validEventId(eventId)) {
            throw new IllegalArgumentException("invalid eventId");
        }
        if (nowMillis <= 0) {
            throw new IllegalArgumentException("nowMillis must be positive");
        }

        Map<Key, Long> candidate = new HashMap<>(seen);
        boolean pruned = prune(candidate, nowMillis);
        Key key = new Key(sourceHash, eventId);
        if (candidate.containsKey(key)) {
            if (pruned) {
                rewriteAtomically(candidate);
                seen.clear();
                seen.putAll(candidate);
            }
            return false;
        }
        if (candidate.size() >= MAX_COUNT) {
            pruneOldest(candidate, candidate.size() - MAX_COUNT + 1);
            pruned = true;
        }
        candidate.put(key, nowMillis);
        if (pruned) {
            rewriteAtomically(candidate);
        } else {
            ensureExistingFile();
            appendForced(lineFor(sourceHash, eventId, nowMillis));
        }
        seen.clear();
        seen.putAll(candidate);
        return true;
    }

    @Override
    public void close() {
        // No resources are held between calls.
    }

    synchronized int sizeForTests() {
        return seen.size();
    }

    private void load() throws IOException {
        seen.clear();
        if (!Files.exists(file)) {
            return;
        }
        long fileSize = Files.size(file);
        if (fileSize == 0L) {
            throw new IOException("corrupt empty seen-event store");
        }
        if (fileSize > MAX_FILE_BYTES) {
            throw new IOException("seen-event store too large");
        }
        requireTerminatingNewline(fileSize);
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String first = reader.readLine();
            if (!HEADER.equals(first)) {
                throw new IOException("unrecognized seen-event store header");
            }
            String line;
            int lineNumber = 1;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (lineNumber > MAX_LOAD_LINES + 1) {
                    throw new IOException("seen-event store has too many lines");
                }
                if (line.isBlank()) {
                    continue;
                }
                String[] parts = line.split("\t", -1);
                if (parts.length != 3 || !DonationParser.isValidSourceHash(parts[0]) || !validEventId(parts[1])) {
                    throw new IOException("corrupt seen-event store at line " + lineNumber);
                }
                long timestamp;
                try {
                    timestamp = Long.parseLong(parts[2]);
                } catch (NumberFormatException ex) {
                    throw new IOException("corrupt seen-event timestamp at line " + lineNumber, ex);
                }
                if (timestamp <= 0) {
                    throw new IOException("corrupt seen-event timestamp at line " + lineNumber);
                }
                seen.put(new Key(parts[0], parts[1]), timestamp);
                if (seen.size() > MAX_COUNT) {
                    throw new IOException("seen-event store has too many entries");
                }
            }
        }
    }

    private void requireTerminatingNewline(long fileSize) throws IOException {
        ByteBuffer last = ByteBuffer.allocate(1);
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            channel.position(fileSize - 1);
            if (channel.read(last) != 1) {
                throw new IOException("corrupt truncated seen-event store");
            }
        }
        last.flip();
        if (last.get() != (byte) '\n') {
            throw new IOException("corrupt truncated seen-event store");
        }
    }

    private static boolean prune(Map<Key, Long> values, long nowMillis) {
        int before = values.size();
        values.entrySet().removeIf(entry -> entry.getValue() <= 0 || nowMillis - entry.getValue() > MAX_AGE_MILLIS);
        return values.size() != before;
    }

    private static void pruneOldest(Map<Key, Long> values, int count) {
        if (count <= 0) {
            return;
        }
        List<Map.Entry<Key, Long>> entries = new ArrayList<>(values.entrySet());
        entries.sort(Comparator.comparingLong(Map.Entry::getValue));
        for (int i = 0; i < count && i < entries.size(); i++) {
            values.remove(entries.get(i).getKey());
        }
    }

    private void ensureExistingFile() throws IOException {
        ensureParent();
        if (!Files.exists(file)) {
            rewriteAtomically(Map.of());
        }
    }

    private void rewriteAtomically(Map<Key, Long> values) throws IOException {
        ensureParent();
        Path tmp = tempPath();
        try (BufferedWriter writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            writer.write(HEADER);
            writer.newLine();
            List<Map.Entry<Key, Long>> entries = new ArrayList<>(values.entrySet());
            entries.sort(Comparator.comparingLong(Map.Entry::getValue));
            for (Map.Entry<Key, Long> entry : entries) {
                writer.write(lineFor(entry.getKey().sourceHash, entry.getKey().eventId, entry.getValue()));
                writer.newLine();
            }
        } catch (IOException | RuntimeException ex) {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
                // Keep original failure.
            }
            throw ex;
        }
        moveAtomically(tmp, file);
    }

    private void appendForced(String line) throws IOException {
        byte[] bytes = (line + "\n").getBytes(StandardCharsets.UTF_8);
        long priorLength = Files.size(file);
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
            channel.write(ByteBuffer.wrap(bytes));
            channel.force(true);
        } catch (IOException | RuntimeException ex) {
            IOException failure = ex instanceof IOException io ? io : new IOException("seen-event append failed", ex);
            try (FileChannel repair = FileChannel.open(file, StandardOpenOption.WRITE)) {
                repair.truncate(priorLength);
                repair.force(true);
            } catch (IOException repairFailure) {
                failure.addSuppressed(repairFailure);
            }
            throw failure;
        }
    }

    private void ensureParent() throws IOException {
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
    }

    private Path tempPath() {
        return file.resolveSibling(file.getFileName() + ".tmp");
    }

    private static void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String lineFor(String sourceHash, String eventId, long nowMillis) {
        return sourceHash + '\t' + eventId + '\t' + nowMillis;
    }

    private static boolean validEventId(String eventId) {
        return eventId != null
                && !eventId.isBlank()
                && eventId.length() <= 240
                && eventId.indexOf('\t') < 0
                && eventId.indexOf('\n') < 0
                && eventId.indexOf('\r') < 0;
    }

    private record Key(String sourceHash, String eventId) {}
}
