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
 * M2 task-05 (red-first) — {@code find_supertypes} tool over the {@code resolve/hierarchy-chain} fixture
 * (interface {@code A} ← {@code B} ← {@code C} ← {@code D}): the transitive supertype closure of a type,
 * rendered with {@code file:line} + depth/edge labels; the position-mode go-to-hierarchy path (resolve a
 * use site to its declaration, then walk up); the end-to-end {@code tools/call}; and bad input.
 */
class FindSupertypesToolTest {

    private static final Path CHAIN = Path.of("src/test/resources/fixtures/resolve/hierarchy-chain");

    private static FindSupertypesTool tool(QueryService svc) {
        return new FindSupertypesTool(() -> svc, BudgetPolicy.defaultPolicy(Metrics.noop()));
    }

    @Test
    void advertisedNameEmbedsJava() {
        assertEquals("find_java_supertypes", tool(null).name());
    }

    @Test
    void rendersTheTransitiveSupertypeClosure(@TempDir Path indexDir) throws Exception {
        try (QueryService svc = ToolTestSupport.queryService(CHAIN, indexDir)) {
            ToolResult r = tool(svc).call(args("{\"symbol\":\"D\"}"));
            assertFalse(r.isError(), () -> r.render());
            String out = r.render();
            assertTrue(out.contains("Supertypes of") && out.contains(": 3"), "the sacred count header: " + out);
            assertTrue(out.contains("C.java") && out.contains("B.java") && out.contains("A.java"),
                    "the whole upward closure {C, B, A}: " + out);
            assertTrue(out.contains("depth 3"), "the root carries its hop depth: " + out);
        }
    }

    @Test
    void positionModeResolvesTheSiteThenWalksUp(@TempDir Path indexDir) throws Exception {
        try (QueryService svc = ToolTestSupport.queryService(CHAIN, indexDir)) {
            // D.java:4  ->  public class D extends C {  — cursor on the C supertype reference.
            String file = CHAIN.resolve("app/D.java").toString();
            ToolResult r = tool(svc).call(args("{\"file\":\"" + file + "\",\"line\":4,\"col\":24}"));
            assertFalse(r.isError(), () -> r.render());
            String out = r.render();
            assertTrue(out.contains("B.java") && out.contains("A.java"), "supertypes of C are {B, A}: " + out);
        }
    }

    @Test
    void noModeIsAToolError(@TempDir Path indexDir) throws Exception {
        try (QueryService svc = ToolTestSupport.queryService(CHAIN, indexDir)) {
            assertTrue(tool(svc).call(JsonValue.NULL).isError(), "no input is a tool error");
        }
    }

    @Test
    void endToEndThroughTheMcpServer(@TempDir Path indexDir) throws Exception {
        try (QueryService svc = ToolTestSupport.queryService(CHAIN, indexDir)) {
            var reply = ToolTestSupport.callThroughServer(tool(svc), "{\"symbol\":\"D\"}");
            assertFalse(ToolTestSupport.isError(reply), "a resolved hierarchy is not an error result");
            assertTrue(ToolTestSupport.textOf(reply).contains("A.java"), ToolTestSupport.textOf(reply));
        }
    }

    private static JsonValue args(String json) {
        return JsonReader.parse(json);
    }
}
