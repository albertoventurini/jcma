package jcma.mcp.json;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import jcma.mcp.json.JsonValue.JsonArray;
import jcma.mcp.json.JsonValue.JsonNumber;
import jcma.mcp.json.JsonValue.JsonObject;
import jcma.mcp.json.JsonValue.JsonString;
import org.junit.jupiter.api.Test;

/**
 * M2 task 1 · the {@link JsonWriter}. Output is deterministic (insertion-order keys, no
 * whitespace), correctly escaped, and forms a fixed point with {@link JsonReader}:
 * {@code parse(write(v)).equals(v)}.
 */
class JsonWriterTest {

    /** Assert the writer↔reader fixed point: serializing then re-parsing yields an equal value. */
    private static void assertFixedPoint(JsonValue v) {
        assertEquals(v, JsonReader.parse(JsonWriter.write(v)), "parse(write(v)) must equal v");
    }

    // ---- exact output ----------------------------------------------------------------------------

    @Test
    void writesPrimitives() {
        assertEquals("\"hi\"", JsonWriter.write(new JsonString("hi")));
        assertEquals("true", JsonWriter.write(JsonValue.TRUE));
        assertEquals("false", JsonWriter.write(JsonValue.FALSE));
        assertEquals("null", JsonWriter.write(JsonValue.NULL));
    }

    @Test
    void emitsNumberRawTokenVerbatim() {
        assertEquals("3.14", JsonWriter.write(new JsonNumber("3.14")));
        assertEquals("1.5E-3", JsonWriter.write(new JsonNumber("1.5E-3")));
        assertEquals("123456789012345678901234567890",
                JsonWriter.write(new JsonNumber("123456789012345678901234567890")));
    }

    @Test
    void writesCompactContainersWithNoWhitespace() {
        JsonObject obj = JsonObject.empty()
                .with("a", new JsonNumber("1"))
                .with("b", new JsonArray(List.of(new JsonNumber("2"), JsonValue.TRUE)));
        assertEquals("{\"a\":1,\"b\":[2,true]}", JsonWriter.write(obj));
    }

    @Test
    void preservesInsertionKeyOrder() {
        JsonObject obj = JsonObject.empty()
                .with("z", new JsonNumber("1"))
                .with("a", new JsonNumber("2"))
                .with("m", new JsonNumber("3"));
        assertEquals("{\"z\":1,\"a\":2,\"m\":3}", JsonWriter.write(obj));
    }

    // ---- escaping --------------------------------------------------------------------------------

    @Test
    void escapesRequiredCharsInOutput() {
        // quote, backslash, and the C-style control escapes.
        String written = JsonWriter.write(new JsonString("\" \\ \b \f \n \r \t"));
        assertEquals("\"\\\" \\\\ \\b \\f \\n \\r \\t\"", written);
    }

    @Test
    void escapesOtherControlCharsAsUnicode() {
        // U+0000 and U+001F have no short escape -> backslash-u00XX (lowercase hex).
        assertEquals("\"\\u0000\"", JsonWriter.write(new JsonString(String.valueOf((char) 0x00))));
        assertEquals("\"\\u001f\"", JsonWriter.write(new JsonString(String.valueOf((char) 0x1f))));
    }

    @Test
    void leavesNonAsciiPrintableUnescaped() {
        // No need to backslash-u-escape printable non-ASCII; it round-trips fine as UTF-8.
        assertEquals("\"é😀\"", JsonWriter.write(new JsonString("é😀")));
    }

    // ---- writer ↔ reader fixed point -------------------------------------------------------------

    @Test
    void everyEscapableCharRoundTrips() {
        StringBuilder sb = new StringBuilder();
        for (char c = 0; c < 0x80; c++) {
            sb.append(c);
        }
        assertFixedPoint(new JsonString(sb.toString()));
    }

    @Test
    void nestedStructureRoundTrips() {
        JsonValue v = JsonObject.empty()
                .with("s", new JsonString("a\tb\n"))
                .with("n", new JsonNumber("-1.5e9"))
                .with("xs", new JsonArray(List.of(JsonValue.NULL, JsonValue.FALSE,
                        JsonObject.empty().with("deep", new JsonString("😀")))));
        assertFixedPoint(v);
    }

    @Test
    void appendableSinkMatchesStringForm() {
        JsonValue v = JsonObject.empty().with("a", new JsonNumber("1")).with("b", JsonValue.TRUE);
        StringBuilder out = new StringBuilder("prefix:");
        JsonWriter.write(v, out);
        assertEquals("prefix:" + JsonWriter.write(v), out.toString());
    }
}
