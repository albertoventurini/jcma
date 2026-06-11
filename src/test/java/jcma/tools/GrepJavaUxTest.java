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
 * M3 task-04 (red-first) — the large-result UX for {@code grep_java}: rank-before-truncate, the
 * {@code output} modes ({@code content}/{@code files}/{@code count}), the repo-relative {@code path}
 * scope, and the core <b>content-with-overflow-fallback</b> (auto-collapse to a per-file aggregation
 * when matches exceed the calibrated threshold). Drives a multi-file, multi-package fixture
 * ({@code fixtures/grepux}: {@code core/Node}, {@code core/Edge}, {@code util/Helper}) with a known
 * {@code id} token map — whole-word vs incidental substring, across three files / two directories.
 *
 * <p>The collapse threshold + top-snippet count are <b>injected</b> (the 4-arg test constructor) so
 * the behaviour is asserted independently of the calibrated production constants.
 */
class GrepJavaUxTest {

    private static final Path UX = Path.of("src/test/resources/fixtures/grepux");

    private static GrepJavaTool tool(QueryService svc) {
        return new GrepJavaTool(() -> svc, BudgetPolicy.defaultPolicy(Metrics.noop()));
    }

    /** A tool with an injected collapse threshold + top-snippet count, for the overflow behaviour. */
    private static GrepJavaTool tool(QueryService svc, int collapseThreshold, int topSnippets) {
        return new GrepJavaTool(() -> svc, BudgetPolicy.defaultPolicy(Metrics.noop()),
                collapseThreshold, topSnippets);
    }

    // ---- content vs overflow ---------------------------------------------------------------------

    @Test
    void belowThresholdReturnsRankedContentSymbolsFirst(@TempDir Path indexDir) throws Exception {
        try (QueryService svc = ToolTestSupport.queryService(UX, indexDir)) {
            // High threshold → content mode. "id" symbols (fields/methods) rank above labelled text.
            ToolResult r = tool(svc, 1000, 5).call(args("{\"query\":\"id\"}"));
            assertFalse(r.isError(), () -> r.render());
            String out = r.render();
            int firstSymbol = firstIndexOf(out, "FIELD", "METHOD");
            int firstText = out.indexOf("[");
            assertTrue(firstSymbol >= 0, "a symbol match is present: " + out);
            assertTrue(firstText >= 0, "a labelled text match is present: " + out);
            assertTrue(firstSymbol < firstText, "symbols rank above text in content mode: " + out);
            assertFalse(out.contains("one per file"), "not collapsed below threshold: " + out);
        }
    }

    @Test
    void aboveThresholdAutoCollapsesToAggregation(@TempDir Path indexDir) throws Exception {
        try (QueryService svc = ToolTestSupport.queryService(UX, indexDir)) {
            // Tiny threshold → "id" (≈14 matches across 3 files) overflows → per-file aggregation, not a wall.
            ToolResult r = tool(svc, 3, 3).call(args("{\"query\":\"id\"}"));
            assertFalse(r.isError(), () -> r.render());
            String out = r.render();
            assertTrue(out.contains("across 3 files"), "header reports the file spread: " + out);
            assertTrue(out.contains("not exhaustive"), "the aggregation is honest about magnitude: " + out);
            assertTrue(out.contains("one per file"), "a one-per-file snippet preview is shown: " + out);
            assertTrue(out.toLowerCase(java.util.Locale.ROOT).contains("narrow"), "a narrowing hint: " + out);
            assertTrue(out.contains("path="), "the hint names path= as the narrowing lever: " + out);
            // Per-file counts are present (navigable map), not a flat truncated wall.
            assertTrue(out.contains("Node.java"), "core/Node.java appears in the per-file counts: " + out);
            assertTrue(out.contains("Helper.java"), "util/Helper.java appears in the per-file counts: " + out);
        }
    }

    @Test
    void autoCollapsePreviewIsOnePerFile(@TempDir Path indexDir) throws Exception {
        try (QueryService svc = ToolTestSupport.queryService(UX, indexDir)) {
            // text tier only, top-2 preview: the two preview entries come from two DISTINCT files.
            ToolResult r = tool(svc, 2, 2).call(args("{\"query\":\"id\",\"match\":\"text\"}"));
            String out = r.render();
            String preview = between(out, "one per file", "Narrow");
            assertTrue(preview.contains("Node.java"), "the preview samples core/Node.java: " + preview);
            assertTrue(preview.contains("Helper.java"), "the preview samples util/Helper.java: " + preview);
            assertEquals(1, countOccurrences(preview, "Node.java"),
                    "one-per-file: core/Node.java appears once in the preview: " + preview);
        }
    }

    // ---- output modes ----------------------------------------------------------------------------

    @Test
    void outputCountReportsTotalsAndPerFileCounts(@TempDir Path indexDir) throws Exception {
        try (QueryService svc = ToolTestSupport.queryService(UX, indexDir)) {
            ToolResult r = tool(svc).call(args("{\"query\":\"id\",\"output\":\"count\"}"));
            assertFalse(r.isError(), () -> r.render());
            String out = r.render();
            assertTrue(out.matches("(?s).*across 3 files.*"), "total file spread reported: " + out);
            assertTrue(out.matches("(?s).*\\d+ symbols.*"), "the symbol total is reported: " + out);
            assertTrue(out.matches("(?s).*\\d+ text.*"), "the text total is reported: " + out);
            assertTrue(out.matches("(?s).*Node\\.java: \\d+ match.*"), "a per-file count line: " + out);
            assertFalse(out.contains("[javadoc]"), "count mode renders no snippet lines: " + out);
        }
    }

    @Test
    void outputFilesListsEveryMatchingFile(@TempDir Path indexDir) throws Exception {
        try (QueryService svc = ToolTestSupport.queryService(UX, indexDir)) {
            String out = tool(svc).call(args("{\"query\":\"id\",\"output\":\"files\"}")).render();
            assertTrue(out.contains("Node.java"), "core/Node.java listed: " + out);
            assertTrue(out.contains("Edge.java"), "core/Edge.java listed: " + out);
            assertTrue(out.contains("Helper.java"), "util/Helper.java listed: " + out);
            assertFalse(out.contains("[string-literal]"), "files mode renders no snippet lines: " + out);
        }
    }

    @Test
    void outputContentIsTheDefaultLineView(@TempDir Path indexDir) throws Exception {
        try (QueryService svc = ToolTestSupport.queryService(UX, indexDir)) {
            String explicit = tool(svc, 1000, 5).call(args("{\"query\":\"id\",\"output\":\"content\"}")).render();
            String implicit = tool(svc, 1000, 5).call(args("{\"query\":\"id\"}")).render();
            assertEquals(implicit, explicit, "output=content is the default");
            assertTrue(explicit.contains("["), "content mode renders labelled snippet lines: " + explicit);
        }
    }

    // ---- path scope ------------------------------------------------------------------------------

    @Test
    void pathRestrictsToSubtreeAndTotalReflectsTheScopedSet(@TempDir Path indexDir) throws Exception {
        try (QueryService svc = ToolTestSupport.queryService(UX, indexDir)) {
            // Scope to util/** (Helper only), limit=2 → the honest marker's total is the SCOPED total (4),
            // not the global ~14 — proving path is applied before ranking/limit.
            ToolResult r = tool(svc).call(args("{\"query\":\"id\",\"path\":\"util/**\",\"limit\":2}"));
            assertFalse(r.isError(), () -> r.render());
            String out = r.render();
            assertTrue(out.contains("Helper.java"), "the scoped file is present: " + out);
            assertFalse(out.contains("Node.java"), "core/ is excluded by path=util/**: " + out);
            assertFalse(out.contains("Edge.java"), "core/ is excluded by path=util/**: " + out);
            assertTrue(out.contains("of 4"), "the marker total is the scoped total (4), not global: " + out);
        }
    }

    // ---- rank-before-truncate (the differentiator over grep) -------------------------------------

    @Test
    void rankBeforeTruncateKeepsTopRankedTextSurvivors(@TempDir Path indexDir) throws Exception {
        try (QueryService svc = ToolTestSupport.queryService(UX, indexDir)) {
            // text tier, limit=2: the two survivors are the top-ranked whole-word lines; the incidental
            // substring line "valid edge label" is cut — grep would cut arbitrarily by position.
            ToolResult r = tool(svc, 1000, 5).call(args("{\"query\":\"id\",\"match\":\"text\",\"limit\":2}"));
            String out = r.render();
            assertTrue(out.contains("id marker present"), "a whole-word line survives the cut: " + out);
            assertFalse(out.contains("valid edge label"), "the incidental-substring line is cut: " + out);
            assertTrue(out.contains("of 7"), "the marker carries the true text total (7): " + out);
        }
    }

    @Test
    void wholeWordRanksAboveIncidentalSubstringInTheTextTier(@TempDir Path indexDir) throws Exception {
        try (QueryService svc = ToolTestSupport.queryService(UX, indexDir)) {
            // Whole-word "id" (the literal "id marker present", "an id is needed soon") ranks above the
            // incidental substring lines ("valid edge label", "Helper for valid ids ...").
            String out = tool(svc, 1000, 50).call(args("{\"query\":\"id\",\"match\":\"text\"}")).render();
            int wholeWord = out.indexOf("id marker present");
            int substring = out.indexOf("valid edge label");
            assertTrue(wholeWord >= 0 && substring >= 0, "both lines present: " + out);
            assertTrue(wholeWord < substring, "whole-word ranks above incidental substring: " + out);
        }
    }

    // ---- helpers ---------------------------------------------------------------------------------

    private static JsonValue args(String json) {
        return JsonReader.parse(json);
    }

    private static int firstIndexOf(String s, String... needles) {
        int best = -1;
        for (String n : needles) {
            int i = s.indexOf(n);
            if (i >= 0 && (best < 0 || i < best)) {
                best = i;
            }
        }
        return best;
    }

    private static String between(String s, String start, String end) {
        int a = s.indexOf(start);
        int b = end == null ? -1 : s.indexOf(end, a < 0 ? 0 : a);
        if (a < 0) {
            return s;
        }
        return s.substring(a, b < 0 ? s.length() : b);
    }

    private static int countOccurrences(String s, String sub) {
        int n = 0;
        for (int i = s.indexOf(sub); i >= 0; i = s.indexOf(sub, i + sub.length())) {
            n++;
        }
        return n;
    }
}
