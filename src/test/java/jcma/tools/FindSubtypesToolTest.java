package jcma.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jcma.mcp.json.JsonReader;
import jcma.mcp.json.JsonValue;
import jcma.obs.Metrics;
import jcma.query.QueryService;
import jcma.response.BudgetPolicy;
import jcma.response.ToolResult;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * M2 task-05 (red-first) — {@code find_subtypes} tool over the {@code resolve/hierarchy-chain} fixture
 * (interface {@code A} ← {@code B} ← {@code C} ← {@code D}): the transitive closure (not just the direct
 * neighbour), each row carrying its {@code file:line} and a {@code depth}/edge-kind relationship label,
 * with the sacred count in the header. Plus the end-to-end {@code tools/call} round-trip and bad input →
 * {@code isError}.
 */
class FindSubtypesToolTest {

    private static final Path CHAIN = Path.of("src/test/resources/fixtures/resolve/hierarchy-chain");

    private static FindSubtypesTool tool(QueryService svc) {
        return new FindSubtypesTool(() -> svc, BudgetPolicy.defaultPolicy(Metrics.noop()));
    }

    @Test
    void advertisedNameEmbedsJava() {
        assertEquals("find_java_subtypes", tool(null).name());
    }

    @Test
    void rendersTheTransitiveClosureWithDepthLabels(@TempDir Path indexDir) throws Exception {
        try (QueryService svc = ToolTestSupport.queryService(CHAIN, indexDir)) {
            ToolResult r = tool(svc).call(args("{\"symbol\":\"A\"}"));
            assertFalse(r.isError(), () -> r.render());
            String out = r.render();
            assertTrue(out.contains("Subtypes of") && out.contains(": 3"), "the sacred count header: " + out);
            assertTrue(out.contains("B.java") && out.contains("C.java") && out.contains("D.java"),
                    "the whole closure with file:line, not just the direct B: " + out);
            assertTrue(out.contains("depth 3"), "the deepest node proves transitivity + carries a depth label: " + out);
        }
    }

    @Test
    void unknownSymbolIsAToolError(@TempDir Path indexDir) throws Exception {
        try (QueryService svc = ToolTestSupport.queryService(CHAIN, indexDir)) {
            assertTrue(tool(svc).call(args("{\"symbol\":\"doesNotExist\"}")).isError(), "an unresolved name is a tool error");
        }
    }

    @Test
    void noInputIsAToolError(@TempDir Path indexDir) throws Exception {
        try (QueryService svc = ToolTestSupport.queryService(CHAIN, indexDir)) {
            assertTrue(tool(svc).call(JsonValue.NULL).isError(), "no input is a tool error");
        }
    }

    @Test
    void endToEndThroughTheMcpServer(@TempDir Path indexDir) throws Exception {
        try (QueryService svc = ToolTestSupport.queryService(CHAIN, indexDir)) {
            var reply = ToolTestSupport.callThroughServer(tool(svc), "{\"symbol\":\"A\"}");
            assertFalse(ToolTestSupport.isError(reply), "a resolved hierarchy is not an error result");
            assertTrue(ToolTestSupport.textOf(reply).contains("D.java"), ToolTestSupport.textOf(reply));
        }
    }

    private static JsonValue args(String json) {
        return JsonReader.parse(json);
    }
}
