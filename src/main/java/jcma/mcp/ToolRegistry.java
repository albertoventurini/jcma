package jcma.mcp;

import jcma.mcp.json.JsonValue;
import jcma.mcp.json.JsonValue.JsonArray;
import jcma.mcp.json.JsonValue.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The {@link ToolHandler}s a server exposes, keyed by name. Built upfront in {@code Serve} (before any
 * index exists) so {@code tools/list} — whose entries are static metadata — answers during the
 * handshake. Insertion-ordered so {@link #schemas()} is deterministic and testable.
 */
public final class ToolRegistry {

    private final Map<String, ToolHandler> handlers = new LinkedHashMap<>();

    /** Register {@code handler}; the last registration for a name wins. */
    public void register(ToolHandler handler) {
        handlers.put(handler.name(), handler);
    }

    /** The handler named {@code name}, or {@code null} if none is registered. */
    public ToolHandler get(String name) {
        return handlers.get(name);
    }

    /** The {@code tools/list} array: one {@code {name, description, inputSchema, _meta}} object per handler. */
    public JsonArray schemas() {
        List<JsonValue> tools = new ArrayList<>(handlers.size());
        for (ToolHandler h : handlers.values()) {
            tools.add(JsonObject.empty()
                    .with("name", JsonValue.of(h.name()))
                    .with("description", JsonValue.of(h.description()))
                    .with("inputSchema", h.schema())
                    .with("_meta", alwaysLoad()));
        }
        return new JsonArray(tools);
    }

    /**
     * The {@code _meta} marking a tool as always-loaded: {@code {"anthropic/alwaysLoad": true}}.
     *
     * <p>Claude Code <em>defers</em> MCP tools once the catalog exceeds a context-budget threshold —
     * only tool names and the server {@code instructions} reach the model, and per-tool descriptions
     * stay hidden behind a {@code ToolSearch} step. jcma is the agent's primary Java-navigation tool,
     * so it must be visible on every turn, not discovered on demand. This flag exempts each tool from
     * deferral <em>from the server side</em>, so any client gets it with no per-machine {@code .mcp.json}
     * config. Keep it: dropping it silently reintroduces the "agent never calls jcma" failure, which
     * description tuning alone cannot fix.
     */
    private static JsonObject alwaysLoad() {
        return JsonObject.empty().with("anthropic/alwaysLoad", JsonValue.of(true));
    }
}
