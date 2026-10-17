package dev.leru.chzzk.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DonationParserTest {
    private static final String SOURCE = "0123456789abcdef0123456789abcdef";

    @Test
    void parsesLiveDonationWithExplicitIdAndExtrasObject() {
        String json = """
                {"cmd":93102,"bdy":[{"msgSn":"abc-1","profile":{"nickname":"Donor"},"msg":"hello","msgTime":1700000000000,"extras":{"payAmount":"1,000"}}]}
                """;

        List<RawDonation> donations = DonationParser.parse(SOURCE, json);

        assertEquals(1, donations.size());
        RawDonation donation = donations.getFirst();
        assertEquals("chzzk:abc-1", donation.eventId());
        assertEquals("Donor", donation.donor());
        assertEquals("hello", donation.message());
        assertEquals(1000L, donation.amount());
        assertEquals(1700000000000L, donation.timestampMillis());
    }

    @Test
    void parsesHistoryDonationOnlyWhenMessageTypeIsDonation() {
        String json = """
                {"cmd":93101,"bdy":[
                  {"msgSn":"chat","messageTypeCode":1,"profile":{"nickname":"Chat"},"msg":"ordinary","msgTime":1700000000000,"payAmount":100},
                  {"msgSn":"donation","msgTypeCode":"10","profile":{"nickname":"Gift"},"msg":"thanks","msgTime":1700000001,"extras":{"donationAmount":"2000"}}
                ]}
                """;

        List<RawDonation> donations = DonationParser.parse(SOURCE, json);

        assertEquals(1, donations.size());
        assertEquals("chzzk:donation", donations.getFirst().eventId());
        assertEquals("Gift", donations.getFirst().donor());
        assertEquals(2_000L, donations.getFirst().amount());
        assertEquals(1_700_000_001_000L, donations.getFirst().timestampMillis());
    }

    @Test
    void ignoresOrdinaryHistoryAndChatCommands() {
        assertTrue(DonationParser.parse(SOURCE, "{\"cmd\":5101,\"bdy\":[]}").isEmpty());
        assertTrue(DonationParser.parse(SOURCE, "{\"cmd\":15101,\"bdy\":[]}").isEmpty());
        assertTrue(DonationParser.parse(SOURCE, "{\"cmd\":93101,\"bdy\":[{\"msgTypeCode\":1,\"payAmount\":100,\"msgSn\":\"x\"}]}").isEmpty());
    }

    @Test
    void rejectsHiddenAmbiguousOrUnsafeAmounts() {
        String json = """
                {"cmd":93102,"bdy":[
                  {"msgSn":"float","payAmount":1.5,"msgTime":1700000000000},
                  {"msgSn":"bool","payAmount":true,"msgTime":1700000000000},
                  {"msgSn":"text","payAmount":"100 cheese","msgTime":1700000000000},
                  {"msgSn":"nested","extras":{"payment":{"payAmount":100}},"msgTime":1700000000000},
                  {"payAmount":100,"msg":"no stable id"}
                ]}
                """;

        assertTrue(DonationParser.parse(SOURCE, json).isEmpty());
    }

    @Test
    void rejectsInvalidOrConflictingAmountsEvenWhenAnotherFieldIsValid() {
        String json = """
                {"cmd":93102,"bdy":[
                  {"msgSn":"invalid-direct","payAmount":"bad","extras":{"donationAmount":100},"msgTime":1700000000000},
                  {"msgSn":"conflict","payAmount":100,"extras":{"donationAmount":200},"msgTime":1700000000000}
                ]}
                """;

        assertTrue(DonationParser.parse(SOURCE, json).isEmpty());
    }

    @Test
    void hashesOverlongExplicitIdsInsteadOfTruncatingIntoCollisions() {
        String id1 = "x".repeat(220) + "a";
        String id2 = "x".repeat(220) + "b";
        String json = "{\"cmd\":93102,\"bdy\":["
                + "{\"msgSn\":\"" + id1 + "\",\"payAmount\":100,\"msgTime\":1700000000000},"
                + "{\"msgSn\":\"" + id2 + "\",\"payAmount\":100,\"msgTime\":1700000000001}]}";

        List<RawDonation> donations = DonationParser.parse(SOURCE, json);

        assertEquals(2, donations.size());
        assertTrue(donations.get(0).eventId().startsWith("chzzk:idsha256:"));
        assertNotEquals(donations.get(0).eventId(), donations.get(1).eventId());
    }

    @Test
    void derivedIdsUseFullRawIdentityAndMessageBeyondDisplayCaps() {
        String prefix = "m".repeat(1000);
        String json = "{\"cmd\":93102,\"bdy\":["
                + "{\"uid\":\"u\",\"nickname\":\"n\",\"msg\":\"" + prefix + "a\",\"msgTime\":1700000000000,\"payAmount\":500},"
                + "{\"uid\":\"u\",\"nickname\":\"n\",\"msg\":\"" + prefix + "b\",\"msgTime\":1700000000000,\"payAmount\":500}]}";

        List<RawDonation> donations = DonationParser.parse(SOURCE, json);

        assertEquals(2, donations.size());
        assertEquals(1000, donations.getFirst().message().length());
        assertNotEquals(donations.get(0).eventId(), donations.get(1).eventId());
    }

    @Test
    void parsesExtrasWhenEncodedAsJsonString() {
        String json = "{\"cmd\":93102,\"bdy\":[{\"msgSn\":\"string-extra\",\"profile\":\"{\\\"nickname\\\":\\\"StringDonor\\\"}\",\"msgTime\":1700000000000,\"extras\":\"{\\\"payAmount\\\":\\\"3000\\\"}\"}]}";

        List<RawDonation> donations = DonationParser.parse(SOURCE, json);

        assertEquals(1, donations.size());
        assertEquals("StringDonor", donations.getFirst().donor());
        assertEquals(3_000L, donations.getFirst().amount());
    }

    @Test
    void derivesStableIdFromTimestampUserAmountAndTextWhenNoExplicitId() {
        String json = """
                {"cmd":93102,"bdy":[{"uid":"u1","nickname":"n","msg":"same","msgTime":1700000000000,"payAmount":500}]}
                """;

        List<RawDonation> first = DonationParser.parse(SOURCE, json);
        List<RawDonation> second = DonationParser.parse(SOURCE, json);

        assertEquals(1, first.size());
        assertEquals(first.getFirst().eventId(), second.getFirst().eventId());
        assertTrue(first.getFirst().eventId().startsWith("chzzk:derived:"));
    }

    @Test
    void validatesSourceHashAndFrameCap() {
        assertTrue(DonationParser.parse("ABC", "{\"cmd\":93102,\"bdy\":[]}").isEmpty());
        String tooLarge = "{\"cmd\":93102,\"bdy\":[]}" + " ".repeat(DonationParser.MAX_FRAME_BYTES + 1);
        assertTrue(DonationParser.parse(SOURCE, tooLarge).isEmpty());
    }
}
