package dev.leru.chzzk.fabric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ChzzkDonationConfigTest {
    @Test
    void defaultsAreVersionOneWithNoBindings() {
        ChzzkDonationConfig config = ChzzkDonationConfig.defaults();

        assertEquals(1, config.version());
        assertTrue(config.bindings().isEmpty());
        assertEquals(32, ChzzkDonationConfig.MAX_BINDINGS);
    }

    @Test
    void acceptsValidBinding() {
        ChzzkDonationConfig.Raw raw = new ChzzkDonationConfig.Raw();
        raw.version = 1;
        ChzzkDonationConfig.RawBinding binding = new ChzzkDonationConfig.RawBinding();
        binding.id = "safe-id_1.2";
        binding.playerUuid = "123e4567-e89b-12d3-a456-426614174000";
        binding.sourceHash = "0123456789abcdef0123456789abcdef";
        binding.enabled = true;
        raw.bindings = List.of(binding);

        ChzzkDonationConfig.LoadResult result = ChzzkDonationConfig.validate(raw);

        assertTrue(result.valid());
        assertEquals("safe-id_1.2", result.config().bindings().getFirst().id());
    }

    @Test
    void rejectsNonCanonicalUuidAndUppercaseSourceHash() {
        ChzzkDonationConfig.Raw raw = new ChzzkDonationConfig.Raw();
        raw.version = 1;
        ChzzkDonationConfig.RawBinding binding = new ChzzkDonationConfig.RawBinding();
        binding.id = "id";
        binding.playerUuid = "123E4567-E89B-12D3-A456-426614174000";
        binding.sourceHash = "0123456789ABCDEF0123456789ABCDEF";
        raw.bindings = List.of(binding);

        assertFalse(ChzzkDonationConfig.validate(raw).valid());
    }

    @Test
    void rejectsDuplicateIdsPlayersAndSources() {
        ChzzkDonationConfig.Raw duplicateIds = twoBindings("same", "00000000-0000-0000-0000-000000000001", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                "same", "00000000-0000-0000-0000-000000000002", "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
        assertFalse(ChzzkDonationConfig.validate(duplicateIds).valid());

        ChzzkDonationConfig.Raw duplicatePlayers = twoBindings("one", "00000000-0000-0000-0000-000000000001", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                "two", "00000000-0000-0000-0000-000000000001", "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
        assertFalse(ChzzkDonationConfig.validate(duplicatePlayers).valid());

        ChzzkDonationConfig.Raw duplicateSources = twoBindings("one", "00000000-0000-0000-0000-000000000001", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                "two", "00000000-0000-0000-0000-000000000002", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        assertFalse(ChzzkDonationConfig.validate(duplicateSources).valid());
    }

    @Test
    void rejectsMoreThanMaxBindings() {
        ChzzkDonationConfig.Raw raw = new ChzzkDonationConfig.Raw();
        raw.version = 1;
        List<ChzzkDonationConfig.RawBinding> bindings = new ArrayList<>();
        for (int i = 0; i < ChzzkDonationConfig.MAX_BINDINGS + 1; i++) {
            ChzzkDonationConfig.RawBinding binding = new ChzzkDonationConfig.RawBinding();
            binding.id = "id" + i;
            binding.playerUuid = String.format("00000000-0000-0000-0000-%012d", i);
            binding.sourceHash = String.format("%032x", i);
            bindings.add(binding);
        }
        raw.bindings = bindings;

        assertFalse(ChzzkDonationConfig.validate(raw).valid());
    }

    @Test
    void jsonValidationRejectsCoercedOrMissingTypes() {
        assertFalse(ChzzkDonationConfig.validateJson("{\"version\":\"1\",\"bindings\":[]}").valid());
        assertFalse(ChzzkDonationConfig.validateJson("{\"bindings\":[]}").valid());
        assertFalse(ChzzkDonationConfig.validateJson("{\"version\":1}").valid());
        assertFalse(ChzzkDonationConfig.validateJson("{\"version\":1,\"bindings\":[{\"id\":\"id\",\"playerUuid\":\"123e4567-e89b-12d3-a456-426614174000\",\"sourceHash\":\"0123456789abcdef0123456789abcdef\",\"enabled\":\"true\"}]}").valid());
    }

    @Test
    void jsonValidationAcceptsExactTypes() {
        ChzzkDonationConfig.LoadResult result = ChzzkDonationConfig.validateJson("{\"version\":1,\"bindings\":[{\"id\":\"id\",\"playerUuid\":\"123e4567-e89b-12d3-a456-426614174000\",\"sourceHash\":\"0123456789abcdef0123456789abcdef\",\"enabled\":true}]}");

        assertTrue(result.valid());
        assertTrue(result.config().bindings().getFirst().enabled());
    }

    private static ChzzkDonationConfig.Raw twoBindings(String id1, String uuid1, String source1,
                                                       String id2, String uuid2, String source2) {
        ChzzkDonationConfig.Raw raw = new ChzzkDonationConfig.Raw();
        raw.version = 1;
        ChzzkDonationConfig.RawBinding first = new ChzzkDonationConfig.RawBinding();
        first.id = id1;
        first.playerUuid = uuid1;
        first.sourceHash = source1;
        ChzzkDonationConfig.RawBinding second = new ChzzkDonationConfig.RawBinding();
        second.id = id2;
        second.playerUuid = uuid2;
        second.sourceHash = source2;
        raw.bindings = List.of(first, second);
        return raw;
    }
}
