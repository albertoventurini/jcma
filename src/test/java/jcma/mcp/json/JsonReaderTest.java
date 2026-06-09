package jcma.mcp.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import jcma.mcp.json.JsonValue.JsonArray;
import jcma.mcp.json.JsonValue.JsonNumber;
import jcma.mcp.json.JsonValue.JsonObject;
import jcma.mcp.json.JsonValue.JsonString;
import org.junit.jupiter.api.Test;

/**
 * M2 task 1 · the recursive-descent {@link JsonReader}. Well-formed inputs parse to the expected
 * {@link JsonValue}; every malformed input <em>throws</em> {@link JsonParseException} rather than
 * silently mis-parsing (the SpikeC failure mode this replaces).
 */
class JsonReaderTest {

    // ---- primitives ------------------------------------------------------------------------------

    @Test
    void parsesPlainString() {
        assertEquals(new JsonString("hello"), JsonReader.parse("\"hello\""));
    }

    @Test
    void parsesEmptyString() {
        assertEquals(new JsonString(""), JsonReader.parse("\"\""));
    }

    @Test
    void parsesBooleansAndNull() {
        assertEquals(JsonValue.TRUE, JsonReader.parse("true"));
        assertEquals(JsonValue.FALSE, JsonReader.parse("false"));
        assertEquals(JsonValue.NULL, JsonReader.parse("null"));
    }

    // ---- string escapes --------------------------------------------------------------------------

    @Test
    void decodesAllSimpleEscapes() {
        // JSON source: "\" \\ \/ \b \f \n \r \t" -> the eight decoded control/quote chars.
        String json = "\"\\\" \\\\ \\/ \\b \\f \\n \\r \\t\"";
        assertEquals(new JsonString("\" \\ / \b \f \n \r \t"), JsonReader.parse(json));
    }

    @Test
    void decodesUnicodeEscape() {
        assertEquals(new JsonString("A"), JsonReader.parse("\"\\u0041\""));
        assertEquals(new JsonString("é"), JsonReader.parse("\"\\u00e9\"")); // é
    }

    @Test
    void decodesSurrogatePair() {
        // 😀 == U+1F600 GRINNING FACE.
        assertEquals(new JsonString("😀"), JsonReader.parse("\"\\uD83D\\uDE00\""));
    }

    // ---- numbers (raw token retained verbatim) ---------------------------------------------------

    @Test
    void parsesIntegerForms() {
        assertEquals(new JsonNumber("42"), JsonReader.parse("42"));
        assertEquals(new JsonNumber("-17"), JsonReader.parse("-17"));
        assertEquals(new JsonNumber("0"), JsonReader.parse("0"));
    }

    @Test
    void parsesDecimalAndExponentForms() {
        assertEquals(new JsonNumber("3.14"), JsonReader.parse("3.14"));
        assertEquals(new JsonNumber("-0.5"), JsonReader.parse("-0.5"));
        assertEquals(new JsonNumber("1e10"), JsonReader.parse("1e10"));
        assertEquals(new JsonNumber("1.5E-3"), JsonReader.parse("1.5E-3"));
        assertEquals(new JsonNumber("2e+9"), JsonReader.parse("2e+9"));
    }

    @Test
    void retainsLargeIntegerLosslessly() {
        // A JSON-RPC id larger than a long must survive as its raw token (lossless round-trip).
        String big = "123456789012345678901234567890";
        assertEquals(new JsonNumber(big), JsonReader.parse(big));
    }

    @Test
    void numberConvertsOnDemand() {
        assertEquals(42, JsonReader.parse("42").asInt());
        assertEquals(9000000000L, JsonReader.parse("9000000000").asLong());
        assertEquals(3.14, JsonReader.parse("3.14").asDouble(), 1e-12);
    }

    // ---- containers ------------------------------------------------------------------------------

    @Test
    void parsesEmptyContainers() {
        assertEquals(new JsonArray(List.of()), JsonReader.parse("[]"));
        assertEquals(new JsonObject(new LinkedHashMap<>()), JsonReader.parse("{}"));
    }

    @Test
    void parsesNestedArray() {
        JsonValue v = JsonReader.parse("[1, [2, 3], 4]");
        JsonArray arr = v.asArray();
        assertEquals(3, arr.elements().size());
        assertEquals(new JsonNumber("2"), arr.elements().get(1).asArray().elements().get(0));
    }

    @Test
    void parsesObjectAndPreservesKeyOrder() {
        JsonValue v = JsonReader.parse("{\"b\": 1, \"a\": 2, \"c\": 3}");
        assertEquals(List.of("b", "a", "c"), List.copyOf(v.asObject().members().keySet()));
        assertEquals(2, v.get("a").asInt());
    }

    @Test
    void parsesMixedNesting() {
        JsonValue v = JsonReader.parse("{\"xs\": [true, null, {\"k\": \"v\"}], \"n\": -1.5}");
        assertEquals("v", v.get("xs").asArray().elements().get(2).get("k").asString());
        assertEquals(new JsonNumber("-1.5"), v.get("n"));
    }

    // ---- whitespace ------------------------------------------------------------------------------

    @Test
    void skipsInsignificantWhitespace() {
        JsonValue v = JsonReader.parse("  \n\t { \"a\" : [ 1 , 2 ] }  \n ");
        assertEquals(List.of(new JsonNumber("1"), new JsonNumber("2")),
                v.get("a").asArray().elements());
    }

    // ---- depth bound -----------------------------------------------------------------------------

    @Test
    void parsesDeepButLegalNesting() {
        // 60 levels of array nesting is under the bound of 64 -> parses.
        String json = "[".repeat(60) + "]".repeat(60);
        JsonValue v = JsonReader.parse(json);
        assertInstanceOf(JsonArray.class, v);
    }

    @Test
    void rejectsOverDeepNesting() {
        // 70 levels exceeds the bound of 64 -> rejected.
        String json = "[".repeat(70) + "]".repeat(70);
        assertThrows(JsonParseException.class, () -> JsonReader.parse(json));
    }

    // ---- malformed input throws ------------------------------------------------------------------

    @Test
    void rejectsTruncatedInput() {
        assertThrows(JsonParseException.class, () -> JsonReader.parse("{\"a\":"));
    }

    @Test
    void rejectsUnterminatedString() {
        assertThrows(JsonParseException.class, () -> JsonReader.parse("\"abc"));
    }

    @Test
    void rejectsTrailingCommaInArray() {
        assertThrows(JsonParseException.class, () -> JsonReader.parse("[1, 2,]"));
    }

    @Test
    void rejectsTrailingCommaInObject() {
        assertThrows(JsonParseException.class, () -> JsonReader.parse("{\"a\": 1,}"));
    }

    @Test
    void rejectsBadEscape() {
        assertThrows(JsonParseException.class, () -> JsonReader.parse("\"a\\xb\""));
    }

    @Test
    void rejectsBadUnicodeHex() {
        assertThrows(JsonParseException.class, () -> JsonReader.parse("\"\\u00ZZ\""));
    }

    @Test
    void rejectsLoneHighSurrogate() {
        assertThrows(JsonParseException.class, () -> JsonReader.parse("\"\\uD83D\""));
    }

    @Test
    void rejectsLoneLowSurrogate() {
        assertThrows(JsonParseException.class, () -> JsonReader.parse("\"\\uDE00\""));
    }

    @Test
    void rejectsTrailingGarbage() {
        assertThrows(JsonParseException.class, () -> JsonReader.parse("{} junk"));
    }

    @Test
    void rejectsTrailingGarbageAfterNumber() {
        assertThrows(JsonParseException.class, () -> JsonReader.parse("42 43"));
    }

    @Test
    void rejectsEmptyInput() {
        assertThrows(JsonParseException.class, () -> JsonReader.parse("   "));
    }

    @Test
    void rejectsBareWord() {
        assertThrows(JsonParseException.class, () -> JsonReader.parse("nul"));
    }

    @Test
    void rejectsLeadingZeroNumber() {
        // JSON forbids leading zeros (e.g. 01); 0 alone is fine.
        assertThrows(JsonParseException.class, () -> JsonReader.parse("01"));
    }

    @Test
    void rejectsBareControlCharInString() {
        // An unescaped raw newline inside a string is illegal.
        assertThrows(JsonParseException.class, () -> JsonReader.parse("\"a\nb\""));
    }

    @Test
    void parseExceptionCarriesOffset() {
        JsonParseException ex =
                assertThrows(JsonParseException.class, () -> JsonReader.parse("[1, 2,]"));
        assertTrue(ex.offset() >= 0, "offset should be set");
    }
}
