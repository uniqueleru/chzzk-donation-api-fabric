package dev.leru.chzzk.core;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

public final class DonationParser {
    public static final int MAX_FRAME_BYTES = 256 * 1024;
    static final int MAX_STRING_CHARS = 4096;
    private static final int MAX_DONOR_CHARS = 80;
    private static final int MAX_MESSAGE_CHARS = 1000;
    private static final int MAX_EVENT_ID_CHARS = 180;
    private static final Pattern SOURCE_HASH = Pattern.compile("[0-9a-f]{32}");
    private static final Pattern DIGITS = Pattern.compile("[0-9]+");
    private static final Pattern GROUPED_DIGITS = Pattern.compile("[0-9]{1,3}(,[0-9]{3})+");
    private static final Gson GSON = new Gson();

    private static final String[] AMOUNT_FIELDS = {
            "payAmount", "donationAmount", "amount", "realAmount", "cheeseAmount", "payValue", "cash", "money", "point"
    };
    private static final String[] EVENT_ID_FIELDS = {
            "msgSn", "msgId", "messageId", "donationId", "donationEventId", "eventId", "payId", "payNo",
            "historyId", "transactionId", "receiptId", "orderId"
    };
    private static final String[] EXTRA_EVENT_ID_FIELDS = {
            "donationId", "donationEventId", "eventId", "payId", "payNo", "historyId", "transactionId",
            "receiptId", "orderId", "msgSn"
    };
    private static final String[] TIMESTAMP_FIELDS = {"timestampMillis", "timestamp", "msgTime", "ctime", "time"};

    private DonationParser() {}

    public static List<RawDonation> parse(String sourceHash, String json) {
        if (!isValidSourceHash(sourceHash) || json == null) {
            return List.of();
        }
        if (json.getBytes(StandardCharsets.UTF_8).length > MAX_FRAME_BYTES) {
            return List.of();
        }
        JsonObject frame;
        try {
            JsonElement element = JsonParser.parseString(json);
            if (!element.isJsonObject()) {
                return List.of();
            }
            frame = element.getAsJsonObject();
        } catch (JsonParseException | IllegalStateException ex) {
            return List.of();
        }
        int cmd = intField(frame.get("cmd"), Integer.MIN_VALUE);
        if (cmd != 93102 && cmd != 93101) {
            return List.of();
        }
        JsonElement body = frame.get("bdy");
        if (body == null || !body.isJsonArray()) {
            return List.of();
        }
        List<RawDonation> out = new ArrayList<>();
        Set<String> seenInFrame = new HashSet<>();
        JsonArray items = body.getAsJsonArray();
        for (JsonElement itemElement : items) {
            if (!itemElement.isJsonObject()) {
                continue;
            }
            JsonObject item = itemElement.getAsJsonObject();
            if (cmd == 93101 && !isDonationMessageType(item)) {
                continue;
            }
            RawDonation donation = parseItem(sourceHash, item);
            if (donation != null && seenInFrame.add(donation.eventId())) {
                out.add(donation);
            }
        }
        return List.copyOf(out);
    }

    public static boolean isValidSourceHash(String sourceHash) {
        return sourceHash != null && SOURCE_HASH.matcher(sourceHash).matches();
    }

    private static RawDonation parseItem(String sourceHash, JsonObject item) {
        JsonObject extras = parseObject(firstPresent(item, "extras", "extra", "extension"));
        Long amount = resolveAmount(item, extras);
        if (amount == null) {
            return null;
        }
        String rawDonor = cleanText(firstString(profile(item), "nickname", "nickName", "userNickname", "name"));
        if (rawDonor.isBlank()) {
            rawDonor = cleanText(firstString(item, "nickname", "userName", "uid"));
        }
        String donor = limit(rawDonor, MAX_DONOR_CHARS);
        if (donor.isBlank()) {
            donor = "익명";
        }
        String rawMessage = cleanText(firstString(item, "msg", "message", "content"));
        String message = limit(rawMessage, MAX_MESSAGE_CHARS);
        Long timestamp = parseTimestamp(firstDirect(item, TIMESTAMP_FIELDS));
        if (timestamp == null) {
            timestamp = parseTimestamp(firstDirect(extras, TIMESTAMP_FIELDS));
        }
        String explicitId = parseExplicitId(firstDirect(item, EVENT_ID_FIELDS));
        if (explicitId == null) {
            explicitId = parseExplicitId(firstDirect(extras, EXTRA_EVENT_ID_FIELDS));
        }
        String eventId;
        if (explicitId != null) {
            eventId = explicitEventId(explicitId);
        } else {
            if (timestamp == null) {
                return null;
            }
            String userKey = userKey(item, rawDonor.isBlank() ? donor : rawDonor);
            eventId = "chzzk:derived:" + sha256Hex(sourceHash + "\u001f" + timestamp + "\u001f" + userKey + "\u001f" + rawDonor + "\u001f" + amount + "\u001f" + rawMessage);
        }
        return new RawDonation(eventId, donor, message, amount, timestamp == null ? 0L : timestamp);
    }

    private static boolean isDonationMessageType(JsonObject item) {
        JsonElement value = firstDirect(item, "messageTypeCode", "msgTypeCode");
        return intField(value, Integer.MIN_VALUE) == 10;
    }

    private static JsonObject profile(JsonObject item) {
        return parseObject(item.get("profile"));
    }

    private static JsonObject parseObject(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return new JsonObject();
        }
        if (element.isJsonObject()) {
            return element.getAsJsonObject();
        }
        String text = stringValue(element);
        if (text == null || text.isBlank() || text.length() > MAX_STRING_CHARS) {
            return new JsonObject();
        }
        try {
            JsonElement parsed = JsonParser.parseString(text);
            return parsed.isJsonObject() ? parsed.getAsJsonObject() : new JsonObject();
        } catch (JsonParseException | IllegalStateException ex) {
            return new JsonObject();
        }
    }

    private static JsonElement firstPresent(JsonObject object, String... names) {
        for (String name : names) {
            if (object.has(name)) {
                JsonElement value = object.get(name);
                if (value != null && !value.isJsonNull()) {
                    return value;
                }
            }
        }
        return null;
    }

    private static JsonElement firstDirect(JsonObject object, String... names) {
        if (object == null) {
            return null;
        }
        for (String name : names) {
            JsonElement value = object.get(name);
            if (value != null && !value.isJsonNull()) {
                String text = stringValue(value);
                if (text == null || !text.isBlank()) {
                    return value;
                }
            }
        }
        return null;
    }

    private static String firstString(JsonObject object, String... names) {
        JsonElement value = firstDirect(object, names);
        String text = stringValue(value);
        if (text == null || text.length() > MAX_STRING_CHARS) {
            return "";
        }
        return text;
    }

    private static String stringValue(JsonElement value) {
        if (value == null || value.isJsonNull()) {
            return null;
        }
        if (value.isJsonPrimitive()) {
            JsonPrimitive primitive = value.getAsJsonPrimitive();
            if (primitive.isString() || primitive.isNumber()) {
                try {
                    return primitive.getAsString();
                } catch (RuntimeException ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    private static Long resolveAmount(JsonObject item, JsonObject extras) {
        AmountValue direct = amountValue(firstDirect(item, AMOUNT_FIELDS));
        AmountValue extra = amountValue(firstDirect(extras, AMOUNT_FIELDS));
        if ((direct.present && !direct.valid) || (extra.present && !extra.valid)) {
            return null;
        }
        if (direct.valid && extra.valid && !direct.value.equals(extra.value)) {
            return null;
        }
        if (direct.valid) {
            return direct.value;
        }
        return extra.valid ? extra.value : null;
    }

    private static AmountValue amountValue(JsonElement value) {
        if (value == null || value.isJsonNull()) {
            return AmountValue.absent();
        }
        Long parsed = parseAmount(value);
        return parsed == null ? AmountValue.invalid() : AmountValue.valid(parsed);
    }

    private static Long parseAmount(JsonElement value) {
        if (value == null || value.isJsonNull() || !value.isJsonPrimitive()) {
            return null;
        }
        JsonPrimitive primitive = value.getAsJsonPrimitive();
        if (primitive.isBoolean()) {
            return null;
        }
        String text;
        if (primitive.isNumber()) {
            text = primitive.getAsString();
            if (!DIGITS.matcher(text).matches()) {
                return null;
            }
        } else if (primitive.isString()) {
            text = primitive.getAsString().trim();
            if (DIGITS.matcher(text).matches()) {
                // keep as-is
            } else if (GROUPED_DIGITS.matcher(text).matches()) {
                text = text.replace(",", "");
            } else {
                return null;
            }
        } else {
            return null;
        }
        try {
            long parsed = Long.parseLong(text);
            return parsed > 0 ? parsed : null;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static Long parseTimestamp(JsonElement value) {
        Long parsed = parsePositiveInteger(value);
        if (parsed == null) {
            return null;
        }
        if (parsed < 10_000_000_000L) {
            return parsed * 1000L;
        }
        return parsed;
    }

    private static Long parsePositiveInteger(JsonElement value) {
        if (value == null || value.isJsonNull() || !value.isJsonPrimitive()) {
            return null;
        }
        JsonPrimitive primitive = value.getAsJsonPrimitive();
        if (primitive.isBoolean()) {
            return null;
        }
        String text = primitive.getAsString().trim();
        if (!DIGITS.matcher(text).matches()) {
            return null;
        }
        try {
            long parsed = Long.parseLong(text);
            return parsed > 0 ? parsed : null;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static String explicitEventId(String explicitId) {
        String prefix = "chzzk:";
        if (explicitId.length() <= MAX_EVENT_ID_CHARS - prefix.length()) {
            return prefix + explicitId;
        }
        return "chzzk:idsha256:" + sha256Hex(explicitId);
    }

    private static String parseExplicitId(JsonElement value) {
        if (value == null || value.isJsonNull() || !value.isJsonPrimitive()) {
            return null;
        }
        JsonPrimitive primitive = value.getAsJsonPrimitive();
        if (primitive.isBoolean()) {
            return null;
        }
        String text = primitive.getAsString().trim();
        if (text.isEmpty() || text.length() > MAX_STRING_CHARS) {
            return null;
        }
        return cleanText(text);
    }

    private static int intField(JsonElement value, int fallback) {
        Long parsed = parsePositiveInteger(value);
        if (parsed == null || parsed > Integer.MAX_VALUE) {
            return fallback;
        }
        return parsed.intValue();
    }

    private static String userKey(JsonObject item, String donor) {
        JsonObject profile = profile(item);
        String key = firstString(item, "uid", "userIdHash", "memberId");
        if (key.isBlank()) {
            key = firstString(profile, "userIdHash", "uid", "id");
        }
        return key.isBlank() ? donor : key;
    }

    private static String cleanText(String text) {
        if (text == null) {
            return "";
        }
        return text.replace('\r', ' ').replace('\n', ' ').replace('\t', ' ').trim();
    }

    private static String limit(String text, int maxChars) {
        if (text == null) {
            return "";
        }
        return text.length() <= maxChars ? text : text.substring(0, maxChars);
    }

    private static String sha256Hex(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8))).toLowerCase(Locale.ROOT);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    static String toJson(Object value) {
        return GSON.toJson(value);
    }

    private record AmountValue(boolean present, boolean valid, Long value) {
        static AmountValue absent() {
            return new AmountValue(false, false, null);
        }

        static AmountValue invalid() {
            return new AmountValue(true, false, null);
        }

        static AmountValue valid(long value) {
            return new AmountValue(true, true, value);
        }
    }
}
