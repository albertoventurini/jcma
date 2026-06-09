package jcma.mcp.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import jcma.mcp.json.JsonValue.JsonObject;
import org.junit.jupiter.api.Test;

/**
 * M2 task 1 · the fixed point over <em>real MCP message shapes</em>: read → write → read returns an
 * equal value. The headline case is a {@code tools/call} carrying a nested {@code arguments} object
 * — the exact shape the M0 SpikeC substring hacks could not read. Each test re-parses the writer's
 * output and asserts structural equality, then drills into the nested object to prove it survived.
 */
class JsonRoundTripTest {

    /** read → write → read must yield an equal value (the fixed-point contract). */
    private static JsonValue roundTrip(String wire) {
        JsonValue first = JsonReader.parse(wire);
        JsonValue second = JsonReader.parse(JsonWriter.write(first));
        assertEquals(first, second, "read→write→read must be a fixed point");
        return second;
    }

    @Test
    void initializeRequest() {
        String wire = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\","
                + "\"params\":{\"protocolVersion\":\"2024-11-05\","
                + "\"capabilities\":{},"
                + "\"clientInfo\":{\"name\":\"claude-code\",\"version\":\"1.0.0\"}}}";
        JsonValue v = roundTrip(wire);
        assertEquals("2.0", v.optString("jsonrpc"));
        assertEquals(1, v.get("id").asInt());
        assertEquals("initialize", v.optString("method"));
        assertEquals("claude-code", v.get("params").get("clientInfo").optString("name"));
    }

    @Test
    void toolsCallWithNestedArgumentsSurvivesIntact() {
        // The SpikeC failure mode: a tools/call whose `arguments` is itself an object.
        String wire = "{\"jsonrpc\":\"2.0\",\"id\":42,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"find_references\","
                + "\"arguments\":{\"file\":\"src/main/java/App.java\","
                + "\"position\":{\"line\":12,\"character\":22},"
                + "\"includeDeclaration\":true}}}";
        JsonValue v = roundTrip(wire);

        assertEquals("tools/call", v.optString("method"));
        JsonValue args = v.get("params").get("arguments");
        assertNotNull(args, "nested arguments object must survive");
        assertEquals("src/main/java/App.java", args.optString("file"));
        assertEquals(12, args.get("position").get("line").asInt());
        assertEquals(22, args.get("position").get("character").asInt());
        assertEquals(true, args.get("includeDeclaration").asBoolean());
    }

    @Test
    void toolResultRoundTrips() {
        String wire = "{\"jsonrpc\":\"2.0\",\"id\":42,"
                + "\"result\":{\"content\":[{\"type\":\"text\","
                + "\"text\":\"App.java:12 g.hello(\\\"world\\\")\"}],"
                + "\"isError\":false}}";
        JsonValue v = roundTrip(wire);
        JsonValue content = v.get("result").get("content");
        assertEquals(1, content.asArray().elements().size());
        assertEquals("App.java:12 g.hello(\"world\")",
                content.asArray().elements().get(0).optString("text"));
        assertEquals(false, v.get("result").get("isError").asBoolean());
    }

    @Test
    void emptyArgumentsObjectSurvives() {
        // tools/list and arg-less tools/call carry an empty object, distinct from absent.
        String wire = "{\"id\":3,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"list_index_stats\",\"arguments\":{}}}";
        JsonValue v = roundTrip(wire);
        JsonValue args = v.get("params").get("arguments");
        assertNotNull(args);
        assertEquals(0, ((JsonObject) args).members().size());
    }
}
