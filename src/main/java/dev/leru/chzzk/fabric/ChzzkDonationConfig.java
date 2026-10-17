package dev.leru.chzzk.fabric;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

public final class ChzzkDonationConfig {
    public static final int VERSION = 1;
    public static final int MAX_BINDINGS = 32;
    public static final long MAX_CONFIG_BYTES = 64 * 1024;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9_.-]{1,48}");
    private static final Pattern SOURCE_HASH = Pattern.compile("[0-9a-f]{32}");

    private final int version;
    private final List<Binding> bindings;

    public ChzzkDonationConfig(int version, List<Binding> bindings) {
        this.version = version;
        this.bindings = bindings == null ? List.of() : List.copyOf(bindings);
    }

    public static ChzzkDonationConfig defaults() {
        return new ChzzkDonationConfig(VERSION, List.of());
    }

    public int version() {
        return version;
    }

    public List<Binding> bindings() {
        return bindings;
    }

    public List<Binding> enabledBindings() {
        return bindings.stream().filter(Binding::enabled).toList();
    }

    public Binding findBySourceHash(String sourceHash) {
        for (Binding binding : bindings) {
            if (binding.sourceHash().equals(sourceHash)) {
                return binding;
            }
        }
        return null;
    }

    public static LoadResult load(Path path) {
        try {
            if (Files.notExists(path)) {
                ChzzkDonationConfig defaults = defaults();
                write(path, defaults);
                return LoadResult.valid(defaults, true);
            }
            long size = Files.size(path);
            if (size > MAX_CONFIG_BYTES) {
                return LoadResult.invalid("Config file is too large");
            }
            String json = Files.readString(path, StandardCharsets.UTF_8);
            return validateJson(json);
        } catch (IOException | RuntimeException e) {
            return LoadResult.invalid("Could not read or validate config");
        }
    }

    public static void write(Path path, ChzzkDonationConfig config) throws IOException {
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Raw raw = new Raw();
        raw.version = config.version;
        raw.bindings = config.bindings.stream().map(binding -> {
            RawBinding rb = new RawBinding();
            rb.id = binding.id();
            rb.playerUuid = binding.playerUuid().toString();
            rb.sourceHash = binding.sourceHash();
            rb.enabled = binding.enabled();
            return rb;
        }).toList();
        try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            GSON.toJson(raw, writer);
        }
    }

    static LoadResult validateJson(String json) {
        try {
            JsonElement root = JsonParser.parseString(json);
            if (!root.isJsonObject()) {
                return LoadResult.invalid("Config root must be an object");
            }
            JsonObject object = root.getAsJsonObject();
            if (!object.has("version") || !isInteger(object.get("version"))) {
                return LoadResult.invalid("Config version is required and must be an integer");
            }
            if (object.get("version").getAsInt() != VERSION) {
                return LoadResult.invalid("Unsupported config version");
            }
            if (!object.has("bindings") || !object.get("bindings").isJsonArray()) {
                return LoadResult.invalid("Config bindings is required and must be an array");
            }
            JsonArray array = object.getAsJsonArray("bindings");
            if (array.size() > MAX_BINDINGS) {
                return LoadResult.invalid("Too many bindings");
            }
            Raw raw = new Raw();
            raw.version = VERSION;
            raw.bindings = new ArrayList<>();
            for (JsonElement element : array) {
                if (!element.isJsonObject()) {
                    return LoadResult.invalid("Binding must be an object");
                }
                JsonObject bindingObject = element.getAsJsonObject();
                LoadResult shape = validateBindingShape(bindingObject);
                if (!shape.valid()) {
                    return shape;
                }
                RawBinding binding = new RawBinding();
                binding.id = bindingObject.get("id").getAsString();
                binding.playerUuid = bindingObject.get("playerUuid").getAsString();
                binding.sourceHash = bindingObject.get("sourceHash").getAsString();
                binding.enabled = bindingObject.get("enabled").getAsBoolean();
                raw.bindings.add(binding);
            }
            return validate(raw);
        } catch (JsonParseException | IllegalStateException | UnsupportedOperationException e) {
            return LoadResult.invalid("Could not parse config JSON");
        }
    }

    private static LoadResult validateBindingShape(JsonObject binding) {
        if (!hasString(binding, "id")) {
            return LoadResult.invalid("Binding id is required and must be a string");
        }
        if (!hasString(binding, "playerUuid")) {
            return LoadResult.invalid("Binding playerUuid is required and must be a string");
        }
        if (!hasString(binding, "sourceHash")) {
            return LoadResult.invalid("Binding sourceHash is required and must be a string");
        }
        if (!binding.has("enabled") || !binding.get("enabled").isJsonPrimitive()
                || !binding.get("enabled").getAsJsonPrimitive().isBoolean()) {
            return LoadResult.invalid("Binding enabled is required and must be a boolean");
        }
        return LoadResult.valid(defaults(), false);
    }

    private static boolean hasString(JsonObject object, String field) {
        return object.has(field) && object.get(field).isJsonPrimitive()
                && object.get(field).getAsJsonPrimitive().isString();
    }

    private static boolean isInteger(JsonElement element) {
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            return false;
        }
        try {
            Number number = element.getAsNumber();
            if (number instanceof Byte || number instanceof Short || number instanceof Integer || number instanceof Long) {
                return true;
            }
            String text = element.getAsString();
            return text.matches("-?\\d+");
        } catch (NumberFormatException | UnsupportedOperationException e) {
            return false;
        }
    }

    static LoadResult validate(Raw raw) {
        if (raw == null) {
            return LoadResult.invalid("Config root must be an object");
        }
        if (raw.version != VERSION) {
            return LoadResult.invalid("Unsupported config version");
        }
        List<RawBinding> rawBindings = raw.bindings == null ? List.of() : raw.bindings;
        if (rawBindings.size() > MAX_BINDINGS) {
            return LoadResult.invalid("Too many bindings");
        }

        Set<String> ids = new HashSet<>();
        Set<UUID> playerUuids = new HashSet<>();
        Set<String> sourceHashes = new HashSet<>();
        List<Binding> bindings = new ArrayList<>();
        for (RawBinding rawBinding : rawBindings) {
            if (rawBinding == null) {
                return LoadResult.invalid("Binding must be an object");
            }
            if (rawBinding.id == null || !SAFE_ID.matcher(rawBinding.id).matches()) {
                return LoadResult.invalid("Binding id is invalid");
            }
            if (!ids.add(rawBinding.id)) {
                return LoadResult.invalid("Duplicate binding id");
            }
            UUID playerUuid;
            try {
                playerUuid = UUID.fromString(rawBinding.playerUuid == null ? "" : rawBinding.playerUuid);
            } catch (IllegalArgumentException e) {
                return LoadResult.invalid("Player UUID is invalid");
            }
            if (!playerUuid.toString().equals(rawBinding.playerUuid)) {
                return LoadResult.invalid("Player UUID must be canonical lowercase UUID text");
            }
            if (!playerUuids.add(playerUuid)) {
                return LoadResult.invalid("Duplicate player UUID");
            }
            if (rawBinding.sourceHash == null || !SOURCE_HASH.matcher(rawBinding.sourceHash).matches()) {
                return LoadResult.invalid("Source hash is invalid");
            }
            String sourceHash = rawBinding.sourceHash.toLowerCase(Locale.ROOT);
            if (!sourceHashes.add(sourceHash)) {
                return LoadResult.invalid("Duplicate source hash");
            }
            bindings.add(new Binding(rawBinding.id, playerUuid, sourceHash, rawBinding.enabled));
        }
        return LoadResult.valid(new ChzzkDonationConfig(VERSION, bindings), false);
    }

    public record Binding(String id, UUID playerUuid, String sourceHash, boolean enabled) {
    }

    public record LoadResult(boolean valid, ChzzkDonationConfig config, boolean createdDefault, String reason) {
        public static LoadResult valid(ChzzkDonationConfig config, boolean createdDefault) {
            return new LoadResult(true, config, createdDefault, "");
        }

        public static LoadResult invalid(String reason) {
            return new LoadResult(false, null, false, reason);
        }
    }

    static final class Raw {
        int version = VERSION;
        List<RawBinding> bindings = List.of();
    }

    static final class RawBinding {
        String id;
        String playerUuid;
        String sourceHash;
        boolean enabled;
    }
}
