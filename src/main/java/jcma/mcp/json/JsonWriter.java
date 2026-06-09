package jcma.mcp.json;

import jcma.mcp.json.JsonValue.JsonArray;
import jcma.mcp.json.JsonValue.JsonBool;
import jcma.mcp.json.JsonValue.JsonNull;
import jcma.mcp.json.JsonValue.JsonNumber;
import jcma.mcp.json.JsonValue.JsonObject;
import jcma.mcp.json.JsonValue.JsonString;
import java.util.Map;

/**
 * A minimal, deterministic JSON serializer for {@link JsonValue}. No pretty-printing (token
 * economy on the MCP wire); object members are emitted in insertion order so output is stable and
 * testable. Numbers are re-emitted as their raw token ({@link JsonValue.JsonNumber#raw()}), so a
 * value round-trips losslessly. Strings are minimally escaped: the required short escapes plus
 * {@code \\u00XX} for the remaining control chars; printable non-ASCII is left as-is (valid UTF-8,
 * fewer bytes).
 */
public final class JsonWriter {

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private JsonWriter() {
    }

    /** Serialize {@code value} to a fresh {@link String}. */
    public static String write(JsonValue value) {
        StringBuilder out = new StringBuilder();
        write(value, out);
        return out.toString();
    }

    /** Serialize {@code value} into {@code out} (the {@link Appendable}/{@link StringBuilder} sink). */
    public static void write(JsonValue value, StringBuilder out) {
        switch (value) {
            case JsonObject o -> writeObject(o, out);
            case JsonArray a -> writeArray(a, out);
            case JsonString s -> writeString(s.value(), out);
            case JsonNumber n -> out.append(n.raw());
            case JsonBool b -> out.append(b.value() ? "true" : "false");
            case JsonNull ignored -> out.append("null");
        }
    }

    private static void writeObject(JsonObject o, StringBuilder out) {
        out.append('{');
        boolean first = true;
        for (Map.Entry<String, JsonValue> e : o.members().entrySet()) {
            if (!first) {
                out.append(',');
            }
            first = false;
            writeString(e.getKey(), out);
            out.append(':');
            write(e.getValue(), out);
        }
        out.append('}');
    }

    private static void writeArray(JsonArray a, StringBuilder out) {
        out.append('[');
        boolean first = true;
        for (JsonValue e : a.elements()) {
            if (!first) {
                out.append(',');
            }
            first = false;
            write(e, out);
        }
        out.append(']');
    }

    private static void writeString(String s, StringBuilder out) {
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append("\\u00")
                                .append(HEX[(c >> 4) & 0xF])
                                .append(HEX[c & 0xF]);
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }
}
