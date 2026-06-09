package jcma.mcp.json;

import java.util.LinkedHashMap;
import java.util.List;

/**
 * A parsed/constructed JSON value — the dependency-free, reflection-free model the M2 MCP surface
 * uses instead of Jackson (PRD §6: full schema control, native-image clean). A {@code sealed}
 * interface over record variants, in the repo's record-heavy factory style (cf. {@link
 * jcma.index.Symbol}, {@link jcma.workspace.NodeDiff}).
 *
 * <p>The model never uses Java {@code null} to mean a present JSON value — an explicit JSON
 * {@code null} is {@link #NULL}. Typed accessors ({@link #asObject()}, {@link #asString()}, …)
 * throw {@link IllegalStateException} on a type mismatch rather than returning {@code null}, so a
 * shape bug surfaces at the read site. {@link #get(String)} / {@link #optString(String)} are the
 * lenient object-lookup conveniences that <em>do</em> return {@code null}/absent cleanly.
 *
 * <p>Numbers are stored as their validated raw token text ({@link JsonNumber}) and converted on
 * demand, so JSON-RPC ids and large ints round-trip losslessly (an echoed id must match the
 * request byte-for-byte) and JSON's single "number" type is not prematurely forced to int-vs-double.
 */
public sealed interface JsonValue
        permits JsonValue.JsonObject, JsonValue.JsonArray, JsonValue.JsonString,
                JsonValue.JsonNumber, JsonValue.JsonBool, JsonValue.JsonNull {

    /** The singleton JSON {@code null}. */
    JsonNull NULL = JsonNull.INSTANCE;
    /** The singleton JSON {@code true}. */
    JsonBool TRUE = JsonBool.TRUE;
    /** The singleton JSON {@code false}. */
    JsonBool FALSE = JsonBool.FALSE;

    // ---- factory helpers (ergonomic construction; cf. repo factory-method style) -----------------

    static JsonValue of(String value) {
        if (value == null) {
            throw new IllegalArgumentException("use JsonValue.NULL for a present JSON null, not Java null");
        }
        return new JsonString(value);
    }

    static JsonValue of(boolean value) {
        return value ? TRUE : FALSE;
    }

    static JsonValue of(int value) {
        return new JsonNumber(Integer.toString(value));
    }

    static JsonValue of(long value) {
        return new JsonNumber(Long.toString(value));
    }

    static JsonValue of(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            throw new IllegalArgumentException("JSON has no NaN/Infinity: " + value);
        }
        return new JsonNumber(Double.toString(value));
    }

    // ---- typed accessors (throw a clear mismatch on the wrong variant) ---------------------------

    default JsonObject asObject() {
        throw mismatch("object");
    }

    default JsonArray asArray() {
        throw mismatch("array");
    }

    default String asString() {
        throw mismatch("string");
    }

    default int asInt() {
        throw mismatch("number");
    }

    default long asLong() {
        throw mismatch("number");
    }

    default double asDouble() {
        throw mismatch("number");
    }

    default boolean asBoolean() {
        throw mismatch("boolean");
    }

    /** Member lookup for an object value, returning {@code null} if the key is absent. */
    default JsonValue get(String key) {
        return asObject().members().get(key);
    }

    /**
     * The string value at {@code key}, or {@code null} if the key is absent or its value is JSON
     * {@code null}. Throws {@link IllegalStateException} if present but not a string.
     */
    default String optString(String key) {
        JsonValue v = get(key);
        if (v == null || v instanceof JsonNull) {
            return null;
        }
        return v.asString();
    }

    private IllegalStateException mismatch(String wanted) {
        return new IllegalStateException(
                "expected JSON " + wanted + " but was " + getClass().getSimpleName());
    }

    // ---- variants --------------------------------------------------------------------------------

    /** A JSON object — insertion-ordered so serialization is deterministic and testable. */
    record JsonObject(LinkedHashMap<String, JsonValue> members) implements JsonValue {
        public JsonObject {
            if (members == null) {
                throw new IllegalArgumentException("object members must not be null");
            }
        }

        /** A fresh empty object (mutable, insertion-ordered). */
        public static JsonObject empty() {
            return new JsonObject(new LinkedHashMap<>());
        }

        /** Put a member and return {@code this} for chaining during construction. */
        public JsonObject with(String key, JsonValue value) {
            members.put(key, value);
            return this;
        }

        @Override
        public JsonObject asObject() {
            return this;
        }
    }

    /** A JSON array. */
    record JsonArray(List<JsonValue> elements) implements JsonValue {
        public JsonArray {
            if (elements == null) {
                throw new IllegalArgumentException("array elements must not be null");
            }
        }

        @Override
        public JsonArray asArray() {
            return this;
        }
    }

    /** A JSON string holding the <em>decoded</em> value (escapes already resolved). */
    record JsonString(String value) implements JsonValue {
        public JsonString {
            if (value == null) {
                throw new IllegalArgumentException("string value must not be null");
            }
        }

        @Override
        public String asString() {
            return value;
        }
    }

    /**
     * A JSON number, stored as its validated raw token text and converted lazily. The writer
     * re-emits {@link #raw()} verbatim so the value round-trips losslessly.
     */
    record JsonNumber(String raw) implements JsonValue {
        public JsonNumber {
            if (raw == null || raw.isEmpty()) {
                throw new IllegalArgumentException("number token must not be null/empty");
            }
        }

        @Override
        public int asInt() {
            return Integer.parseInt(raw);
        }

        @Override
        public long asLong() {
            return Long.parseLong(raw);
        }

        @Override
        public double asDouble() {
            return Double.parseDouble(raw);
        }
    }

    /** A JSON boolean — a two-constant singleton. */
    record JsonBool(boolean value) implements JsonValue {
        static final JsonBool TRUE = new JsonBool(true);
        static final JsonBool FALSE = new JsonBool(false);

        static JsonBool of(boolean value) {
            return value ? TRUE : FALSE;
        }

        @Override
        public boolean asBoolean() {
            return value;
        }
    }

    /** JSON {@code null} — an explicit singleton (never Java {@code null} for a present value). */
    record JsonNull() implements JsonValue {
        static final JsonNull INSTANCE = new JsonNull();
    }
}
