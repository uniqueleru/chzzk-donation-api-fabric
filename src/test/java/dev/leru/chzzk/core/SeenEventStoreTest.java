package dev.leru.chzzk.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class SeenEventStoreTest {
    private static final String SOURCE = "0123456789abcdef0123456789abcdef";

    @TempDir
    Path tempDir;

    @Test
    void persistsBeforeReturningNewAndReloadsDuplicate() throws Exception {
        Path file = tempDir.resolve("seen.tsv");
        SeenEventStore store = new SeenEventStore(file);

        assertTrue(store.markIfNew(SOURCE, "chzzk:1", 1_700_000_000_000L));
        assertTrue(Files.readString(file).contains("chzzk:1"));
        assertFalse(store.markIfNew(SOURCE, "chzzk:1", 1_700_000_000_001L));

        SeenEventStore reloaded = new SeenEventStore(file);
        assertFalse(reloaded.markIfNew(SOURCE, "chzzk:1", 1_700_000_000_002L));
        assertTrue(reloaded.markIfNew(SOURCE, "chzzk:2", 1_700_000_000_003L));
    }

    @Test
    void rejectsEmptyStoreInsteadOfAppendingToUnreadableFile() throws Exception {
        Path file = tempDir.resolve("empty.tsv");
        Files.writeString(file, "");

        IOException ex = assertThrows(IOException.class, () -> new SeenEventStore(file));

        assertTrue(ex.getMessage().contains("empty"));
        assertEquals("", Files.readString(file));
    }

    @Test
    void rejectsTruncatedStoreWithoutTerminatingNewline() throws Exception {
        Path file = tempDir.resolve("truncated.tsv");
        Files.writeString(file, "chzzk-seen-v1");

        IOException ex = assertThrows(IOException.class, () -> new SeenEventStore(file));

        assertTrue(ex.getMessage().contains("truncated"));
    }

    @Test
    void rejectsStoreWithTooManyEntries() throws Exception {
        Path file = tempDir.resolve("too-many.tsv");
        StringBuilder builder = new StringBuilder("chzzk-seen-v1\n");
        for (int i = 0; i <= SeenEventStore.MAX_COUNT; i++) {
            builder.append(SOURCE).append('\t').append("chzzk:").append(i).append('\t').append(1_700_000_000_000L + i).append('\n');
        }
        Files.writeString(file, builder.toString());

        IOException ex = assertThrows(IOException.class, () -> new SeenEventStore(file));

        assertTrue(ex.getMessage().contains("too many"));
    }

    @Test
    void failedPruneRewriteDoesNotRollbackInMemoryUntilPersisted() throws Exception {
        Path file = tempDir.resolve("seen.tsv");
        SeenEventStore store = new SeenEventStore(file);
        long now = 1_700_000_000_000L;
        assertTrue(store.markIfNew(SOURCE, "chzzk:old", now - 8L * 24L * 60L * 60L * 1000L));
        Files.createDirectory(tempDir.resolve("seen.tsv.tmp"));

        assertThrows(IOException.class, () -> store.markIfNew(SOURCE, "chzzk:new", now));

        assertEquals(1, store.sizeForTests());
        assertTrue(Files.readString(file).contains("chzzk:old"));
    }

    @Test
    void rejectsCorruptStoreInsteadOfResetting() throws Exception {
        Path file = tempDir.resolve("corrupt.tsv");
        Files.writeString(file, "chzzk-seen-v1\nnot-a-valid-line\n");

        IOException ex = assertThrows(IOException.class, () -> new SeenEventStore(file));
        assertTrue(ex.getMessage().contains("corrupt"));
        assertEquals("chzzk-seen-v1\nnot-a-valid-line\n", Files.readString(file));
    }

    @Test
    void prunesEntriesOlderThanSevenDays() throws Exception {
        Path file = tempDir.resolve("seen.tsv");
        SeenEventStore store = new SeenEventStore(file);
        long now = 1_700_000_000_000L;

        assertTrue(store.markIfNew(SOURCE, "chzzk:old", now - 8L * 24L * 60L * 60L * 1000L));
        assertTrue(store.markIfNew(SOURCE, "chzzk:new", now));
        assertTrue(store.markIfNew(SOURCE, "chzzk:old", now + 1));

        SeenEventStore reloaded = new SeenEventStore(file);
        assertFalse(reloaded.markIfNew(SOURCE, "chzzk:old", now + 2));
        assertFalse(reloaded.markIfNew(SOURCE, "chzzk:new", now + 2));
    }

    @Test
    void validatesInputs() throws Exception {
        SeenEventStore store = new SeenEventStore(tempDir.resolve("seen.tsv"));

        assertThrows(IllegalArgumentException.class, () -> store.markIfNew("ABC", "chzzk:1", 1));
        assertThrows(IllegalArgumentException.class, () -> store.markIfNew(SOURCE, "bad\n", 1));
        assertThrows(IllegalArgumentException.class, () -> store.markIfNew(SOURCE, "chzzk:1", 0));
    }
}
