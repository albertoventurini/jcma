package jcma.mcp.json;

import jcma.mcp.json.JsonValue.JsonArray;
import jcma.mcp.json.JsonValue.JsonNumber;
import jcma.mcp.json.JsonValue.JsonObject;
import jcma.mcp.json.JsonValue.JsonString;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * A dependency-free recursive-descent JSON parser over a single in-memory document (one MCP
 * message per call). Reflection-free so the native-image surface stays clean (M2 task 1). All
 * malformed input — truncation, trailing garbage, bad escapes, lone surrogates, over-deep nesting —
 * throws {@link JsonParseException} carrying the offset, never a silent mis-parse (unlike the M0
 * {@code SpikeC} substring hacks this replaces).
 *
 * <p>Numbers are validated against the JSON grammar but kept as their verbatim token text in a
 * {@link JsonNumber}, so JSON-RPC ids and large ints round-trip losslessly.
 */
public final class JsonReader {

    /** ~10× real MCP nesting (a {@code tools/call} {@code arguments} is ~3–5 deep); rejects pathological nesting. */
    private static final int MAX_DEPTH = 64;

    private final String s;
    private int pos;
    private int depth;

    private JsonReader(String s) {
        this.s = s;
    }

    /** Parse {@code text} as a single JSON document. */
    public static JsonValue parse(String text) {
        if (text == null) {
            throw new JsonParseException("null input", 0);
        }
        JsonReader r = new JsonReader(text);
        JsonValue v = r.readValue();
        r.skipWs();
        if (r.pos != r.s.length()) {
            throw r.error("trailing garbage after JSON value");
        }
        return v;
    }

    private JsonValue readValue() {
        skipWs();
        if (pos >= s.length()) {
            throw error("unexpected end of input");
        }
        char c = s.charAt(pos);
        return switch (c) {
            case '{' -> readObject();
            case '[' -> readArray();
            case '"' -> new JsonString(readString());
            case 't' -> readLiteral("true", JsonValue.TRUE);
            case 'f' -> readLiteral("false", JsonValue.FALSE);
            case 'n' -> readLiteral("null", JsonValue.NULL);
            default -> {
                if (c == '-' || (c >= '0' && c <= '9')) {
                    yield readNumber();
                }
                throw error("unexpected character '" + c + "'");
            }
        };
    }

    private JsonValue readObject() {
        enter();
        pos++; // consume '{'
        LinkedHashMap<String, JsonValue> members = new LinkedHashMap<>();
        skipWs();
        if (peek() == '}') {
            pos++;
            depth--;
            return new JsonObject(members);
        }
        while (true) {
            skipWs();
            if (peek() != '"') {
                throw error("expected '\"' to start an object key");
            }
            String key = readString();
            skipWs();
            if (peek() != ':') {
                throw error("expected ':' after object key");
            }
            pos++; // consume ':'
            members.put(key, readValue());
            skipWs();
            char c = peek();
            if (c == ',') {
                pos++;
            } else if (c == '}') {
                pos++;
                break;
            } else {
                throw error("expected ',' or '}' in object");
            }
        }
        depth--;
        return new JsonObject(members);
    }

    private JsonValue readArray() {
        enter();
        pos++; // consume '['
        List<JsonValue> elements = new ArrayList<>();
        skipWs();
        if (peek() == ']') {
            pos++;
            depth--;
            return new JsonArray(elements);
        }
        while (true) {
            elements.add(readValue());
            skipWs();
            char c = peek();
            if (c == ',') {
                pos++;
            } else if (c == ']') {
                pos++;
                break;
            } else {
                throw error("expected ',' or ']' in array");
            }
        }
        depth--;
        return new JsonArray(elements);
    }

    private String readString() {
        pos++; // consume opening '"'
        StringBuilder sb = new StringBuilder();
        while (true) {
            if (pos >= s.length()) {
                throw error("unterminated string");
            }
            char c = s.charAt(pos++);
            if (c == '"') {
                return sb.toString();
            }
            if (c == '\\') {
                readEscape(sb);
            } else if (c < 0x20) {
                throw error("unescaped control character U+" + hex4(c) + " in string");
            } else {
                sb.append(c);
            }
        }
    }

    private void readEscape(StringBuilder sb) {
        if (pos >= s.length()) {
            throw error("unterminated escape");
        }
        char e = s.charAt(pos++);
        switch (e) {
            case '"' -> sb.append('"');
            case '\\' -> sb.append('\\');
            case '/' -> sb.append('/');
            case 'b' -> sb.append('\b');
            case 'f' -> sb.append('\f');
            case 'n' -> sb.append('\n');
            case 'r' -> sb.append('\r');
            case 't' -> sb.append('\t');
            case 'u' -> readUnicodeEscape(sb);
            default -> throw error("invalid escape '\\" + e + "'");
        }
    }

    private void readUnicodeEscape(StringBuilder sb) {
        char hi = readHex4();
        if (Character.isHighSurrogate(hi)) {
            // Must be immediately followed by a \\uXXXX low surrogate to form a valid pair.
            if (pos + 1 >= s.length() || s.charAt(pos) != '\\' || s.charAt(pos + 1) != 'u') {
                throw error("lone high surrogate U+" + hex4(hi));
            }
            pos += 2; // consume "\\u"
            char lo = readHex4();
            if (!Character.isLowSurrogate(lo)) {
                throw error("high surrogate U+" + hex4(hi) + " not followed by a low surrogate");
            }
            sb.append(hi).append(lo);
        } else if (Character.isLowSurrogate(hi)) {
            throw error("lone low surrogate U+" + hex4(hi));
        } else {
            sb.append(hi);
        }
    }

    private char readHex4() {
        if (pos + 4 > s.length()) {
            throw error("truncated \\u escape");
        }
        int v = 0;
        for (int i = 0; i < 4; i++) {
            char c = s.charAt(pos++);
            int d = hexDigit(c);
            if (d < 0) {
                throw error("invalid hex digit '" + c + "' in \\u escape");
            }
            v = (v << 4) | d;
        }
        return (char) v;
    }

    private JsonValue readNumber() {
        int start = pos;
        if (peek() == '-') {
            pos++;
        }
        // integer part: '0' alone, or a non-zero digit followed by more digits.
        char c = peek();
        if (c == '0') {
            pos++;
        } else if (c >= '1' && c <= '9') {
            pos++;
            while (isDigit(peek())) {
                pos++;
            }
        } else {
            throw error("invalid number: expected digit");
        }
        // fraction
        if (peek() == '.') {
            pos++;
            if (!isDigit(peek())) {
                throw error("invalid number: expected digit after '.'");
            }
            while (isDigit(peek())) {
                pos++;
            }
        }
        // exponent
        char e = peek();
        if (e == 'e' || e == 'E') {
            pos++;
            char sign = peek();
            if (sign == '+' || sign == '-') {
                pos++;
            }
            if (!isDigit(peek())) {
                throw error("invalid number: expected digit in exponent");
            }
            while (isDigit(peek())) {
                pos++;
            }
        }
        return new JsonNumber(s.substring(start, pos));
    }

    private JsonValue readLiteral(String literal, JsonValue value) {
        if (pos + literal.length() > s.length()
                || !s.regionMatches(pos, literal, 0, literal.length())) {
            throw error("invalid literal: expected '" + literal + "'");
        }
        pos += literal.length();
        return value;
    }

    // ---- helpers ---------------------------------------------------------------------------------

    private void enter() {
        if (++depth > MAX_DEPTH) {
            throw error("nesting too deep (max " + MAX_DEPTH + ")");
        }
    }

    private void skipWs() {
        while (pos < s.length()) {
            char c = s.charAt(pos);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                pos++;
            } else {
                break;
            }
        }
    }

    /** The current char, or {@code '\0'} at end of input (a sentinel no JSON token starts with). */
    private char peek() {
        return pos < s.length() ? s.charAt(pos) : '\0';
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private static int hexDigit(char c) {
        if (c >= '0' && c <= '9') {
            return c - '0';
        }
        if (c >= 'a' && c <= 'f') {
            return c - 'a' + 10;
        }
        if (c >= 'A' && c <= 'F') {
            return c - 'A' + 10;
        }
        return -1;
    }

    private static String hex4(char c) {
        String h = Integer.toHexString(c).toUpperCase();
        return "0".repeat(4 - h.length()) + h;
    }

    private JsonParseException error(String message) {
        return new JsonParseException(message, pos);
    }
}
