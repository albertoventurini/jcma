package jcma.tools;

import jcma.index.SearchSpec;
import jcma.index.SymbolKind;
import jcma.mcp.ToolHandler;
import jcma.mcp.json.JsonValue;
import jcma.mcp.json.JsonValue.JsonObject;
import jcma.query.QueryService;
import jcma.query.QueryTimeoutException;
import jcma.response.BudgetPolicy;
import jcma.response.Shaping;
import jcma.response.ToolResult;
import jcma.response.ToolResult.Fragment;
import jcma.response.ToolResult.TextFragment;
import jcma.session.SymbolHit;
import jcma.session.TextHit;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;
import java.util.regex.PatternSyntaxException;

/**
 * The {@code grep_java} §6 tool (M3 task-02): the agent's own verb, with <b>no symbol-hole</b>. It
 * returns semantic <b>symbol</b> matches first (the {@link SearchSymbolsTool} path) and degrades to
 * labelled <b>text</b> matches — string-literal / comment / Javadoc — so a token that exists only as
 * text (a log message, a TODO) still answers where {@code search_java_symbols} would silently miss.
 * This is the reflex flip: use it instead of {@code grep} for {@code .java}.
 *
 * <p>{@code query} is a <b>regular expression by default</b> (the agent's grep reflex; D-a), applied
 * with uniform semantics across both tiers (D-b): {@code fixed_string} opts into literal matching
 * (grep {@code -F}), {@code case_sensitive} (default true; D-c) opts case mode. Anchors are per
 * physical line ({@link java.util.regex.Pattern#MULTILINE}; {@code .} does not cross {@code \n} — D-d).
 * The match policy is the swappable {@link SearchSpec}, so a metachar-free case-sensitive query keeps
 * the trigram/{@code indexOf} fast path unchanged.
 *
 * <p>A combined display {@code limit} (symbols first) carries an <em>honest</em> {@code Showing K of N}
 * marker derived from an uncapped scan (the search primitives are uncapped, so the true total is free);
 * the result is routed through the existing swappable {@link BudgetPolicy} token seam.
 */
public final class GrepJavaTool implements ToolHandler {

    /** Default combined display cap (across both tiers) when {@code limit} is omitted. */
    static final int DEFAULT_LIMIT = 50;

    /**
     * Total-match count above which {@code output=content} auto-collapses to the per-file aggregation
     * view (M3 task-04, D3a). <b>Calibrated 2026-06-11</b> on the jcma corpus: a rendered match line is
     * ~38 tokens (stable 32–41), so the {@link BudgetPolicy#DEFAULT_CAP}=4000 budget is reached at
     * 4000/38 ≈ 105 lines; 100 is rounded down for headroom. The corpus distribution shows a clean
     * cliff (real queries are ≤9 or ≥108 matches), so 100 separates "show content" from "show a map".
     * It doubles as a budget guarantee: content (≤100 × 38 ≈ 3800 tok) fits 4000 regardless of {@code
     * limit}, so the budget backstop is a true safety net, not a routine rung.
     */
    static final int COLLAPSE_THRESHOLD = 100;

    /** One-per-file snippet sample shown in the auto-collapse view (~190 tokens). Calibrated 2026-06-11. */
    static final int TOP_SNIPPETS = 5;

    /** Which tier(s) to search. {@code BOTH} is the no-hole default. */
    private enum Match { SYMBOLS, TEXT, BOTH }

    /** Output shape: ranked {@code content} lines (default), or a per-file {@code files}/{@code count} map. */
    private enum Output { CONTENT, FILES, COUNT }

    private final Supplier<QueryService> svc;
    private final BudgetPolicy budget;
    private final int collapseThreshold;
    private final int topSnippets;

    public GrepJavaTool(Supplier<QueryService> svc, BudgetPolicy budget) {
        this(svc, budget, COLLAPSE_THRESHOLD, TOP_SNIPPETS);
    }

    /** Test seam: an injectable collapse threshold + top-snippet count (the calibrated defaults above). */
    GrepJavaTool(Supplier<QueryService> svc, BudgetPolicy budget, int collapseThreshold, int topSnippets) {
        this.svc = svc;
        this.budget = budget;
        this.collapseThreshold = collapseThreshold;
        this.topSnippets = topSnippets;
    }

    @Override
    public String name() {
        return "grep_java";
    }

    @Override
    public String description() {
        return "Search Java sources — use instead of grep for `.java`. Returns semantic Java symbol "
                + "matches first, then plain string-literal / comment / Javadoc text matches, so a token "
                + "that exists only as text still hits. `query` = regular expression (default; `^`/`$` "
                + "anchor per line); set `fixed_string` for a literal substring. `case_sensitive` defaults "
                + "true. `match` = symbols | text | both (default both). `kind` filters the symbol tier. "
                + "`path` = a repo-relative glob (e.g. `src/main/**`, `**/*Test.java`) scoping the search. "
                + "`output` = content (ranked lines, default) | files | count (per-file aggregation). Broad "
                + "queries rank before truncating and auto-collapse to a per-file map. `limit` (default "
                + DEFAULT_LIMIT + ") caps the combined, symbols-first content.";
    }

    @Override
    public JsonValue schema() {
        JsonObject props = JsonObject.empty()
                .with("query", ToolSupport.typed("string",
                        "Regular expression to find (default); `^`/`$` anchor per line. Set `fixed_string` "
                                + "for a literal substring."))
                .with("fixed_string", ToolSupport.typed("boolean",
                        "Treat `query` as a literal substring, not a regex (grep -F). Default false."))
                .with("case_sensitive", ToolSupport.typed("boolean",
                        "Case-sensitive matching. Default true."))
                .with("match", ToolSupport.typed("string",
                                "Which tier(s) to search (default both).")
                        .with("enum", new JsonValue.JsonArray(List.of(
                                JsonValue.of("symbols"), JsonValue.of("text"), JsonValue.of("both")))))
                .with("kind", ToolSupport.typed("string",
                        "Optional declaration-kind filter (symbol tier only).").with("enum", ToolSupport.kindEnum()))
                .with("path", ToolSupport.typed("string",
                        "Optional repo-relative glob scoping the search (e.g. `src/main/**`, `**/*Test.java`)."))
                .with("output", ToolSupport.typed("string",
                                "Output shape (default content).")
                        .with("enum", new JsonValue.JsonArray(List.of(
                                JsonValue.of("content"), JsonValue.of("files"), JsonValue.of("count")))))
                .with("limit", ToolSupport.typed("integer",
                        "Max combined results, symbols first (default " + DEFAULT_LIMIT + ")."));
        return JsonObject.empty()
                .with("type", JsonValue.of("object"))
                .with("properties", props)
                .with("required", new JsonValue.JsonArray(List.of(JsonValue.of("query"))));
    }

    @Override
    public ToolResult call(JsonValue args) {
        JsonObject in = ToolSupport.obj(args);
        String query = in.optString("query");
        if (query == null || query.isEmpty()) {
            return ToolResult.text("grep_java: provide a non-empty query");
        }

        Match match = Match.BOTH;
        String matchArg = in.optString("match");
        if (matchArg != null) {
            try {
                match = Match.valueOf(matchArg.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return ToolResult.error("unknown match '" + matchArg + "' (expected symbols | text | both)");
            }
        }

        SymbolKind kind = null;
        String kindArg = in.optString("kind");
        if (kindArg != null) {
            try {
                kind = SymbolKind.valueOf(kindArg.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return ToolResult.error("unknown kind '" + kindArg + "'");
            }
        }

        Output output = Output.CONTENT;
        String outputArg = in.optString("output");
        if (outputArg != null) {
            try {
                output = Output.valueOf(outputArg.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return ToolResult.error("unknown output '" + outputArg + "' (expected content | files | count)");
            }
        }

        String pathArg = in.optString("path");
        String pathGlob = (pathArg == null || pathArg.isEmpty()) ? null : pathArg;

        Integer limitArg = ToolSupport.optInt(in, "limit");
        int limit = limitArg == null ? DEFAULT_LIMIT : limitArg;

        boolean fixedString = ToolSupport.optBool(in, "fixed_string", false);
        boolean caseSensitive = ToolSupport.optBool(in, "case_sensitive", true);
        SearchSpec spec = SearchSpec.of(query, fixedString, caseSensitive);
        try {
            spec.validate(); // surface a malformed regex as a clean error before any scan
        } catch (PatternSyntaxException e) {
            return ToolResult.error("grep_java: invalid regex: " + e.getMessage());
        }

        try {
            QueryService q = svc.get();
            // Uncapped + path-scoped scans (Integer.MAX_VALUE): the true totals are free (the scan is
            // uncapped) and the path filter runs before ranking/limit, so every total below is honest.
            List<SymbolHit> symbols = match == Match.TEXT
                    ? List.of()
                    : q.searchSymbols(spec, kind, pathGlob, Integer.MAX_VALUE, ToolSupport.DEFAULT_DEADLINE);
            List<TextHit> text = match == Match.SYMBOLS
                    ? List.of()
                    : q.searchText(spec, pathGlob, Integer.MAX_VALUE, ToolSupport.DEFAULT_DEADLINE);

            int totalSymbols = symbols.size();
            int totalText = text.size();
            int total = totalSymbols + totalText;
            if (total == 0) {
                return ToolResult.text("no matches for '" + query + "'");
            }

            // The combined ranked hit stream (symbols first, then text) — the one source for the per-file
            // aggregation, the one-per-file preview, and the capped content view.
            Path root = q.repoRoot();
            List<Entry> entries = new ArrayList<>(total);
            for (SymbolHit h : symbols) {
                entries.add(new Entry(relFile(root, h.file()), Shaping.symbol(h.symbol(), h.file())));
            }
            for (TextHit h : text) {
                entries.add(new Entry(relFileStr(root, h.file()), Shaping.lineMatch(h)));
            }
            List<ToolResult.FileCount> perFile = aggregate(entries);

            ToolResult result = switch (output) {
                case COUNT -> ToolResult.of(List.of(
                        new TextFragment(countHeader(total, perFile.size(), totalSymbols, totalText)),
                        new ToolResult.MatchRollupFragment(perFile)));
                case FILES -> ToolResult.of(List.of(
                        new TextFragment(perFile.size() + (perFile.size() == 1 ? " file" : " files")
                                + " with matches (" + total + " total)"),
                        new ToolResult.MatchRollupFragment(perFile)));
                case CONTENT -> total > collapseThreshold
                        ? collapsed(entries, perFile, total, totalSymbols, totalText)
                        : content(entries, total, totalSymbols, totalText, limit);
            };
            return budget.apply(name(), result);
        } catch (IOException | QueryTimeoutException e) {
            return ToolResult.error("grep_java failed: " + e.getMessage());
        }
    }

    /** One ranked hit: its repo-relative file (the aggregation key) + its rendered fragment. */
    private record Entry(String file, Fragment fragment) {}

    /** Per-file counts over the ranked hits, in first-seen (rank) order. */
    private static List<ToolResult.FileCount> aggregate(List<Entry> entries) {
        java.util.LinkedHashMap<String, Integer> counts = new java.util.LinkedHashMap<>();
        for (Entry e : entries) {
            counts.merge(e.file(), 1, Integer::sum);
        }
        List<ToolResult.FileCount> out = new ArrayList<>(counts.size());
        counts.forEach((file, n) -> out.add(new ToolResult.FileCount(file, n)));
        return out;
    }

    /** Ranked content (symbols then text) capped at {@code limit}, with the honest non-exhaustive marker. */
    private static ToolResult content(List<Entry> entries, int total, int totalSymbols, int totalText, int limit) {
        List<Fragment> out = new ArrayList<>(Math.min(limit, total) + 1);
        for (Entry e : entries) {
            if (out.size() >= limit) {
                break;
            }
            out.add(e.fragment());
        }
        int shown = out.size();
        if (shown < total) {
            out.add(new TextFragment("Showing " + shown + " of " + total + " total matches ("
                    + totalSymbols + " symbols, " + totalText + " text) — not exhaustive; "
                    + "raise `limit` or narrow the query."));
        }
        return ToolResult.of(out);
    }

    /**
     * The auto-collapse view (D3): a per-file count map (which files hold the matches, how many each),
     * a diverse one-per-file snippet sample, and a narrowing hint — instead of a truncated wall. The
     * sacred total + the symbol/text split + the not-exhaustive marker keep it honest about magnitude.
     */
    private ToolResult collapsed(List<Entry> entries, List<ToolResult.FileCount> perFile, int total,
            int totalSymbols, int totalText) {
        List<Fragment> out = new ArrayList<>();
        out.add(new TextFragment(countHeader(total, perFile.size(), totalSymbols, totalText)
                + " — not exhaustive; showing per-file counts."));
        out.add(new ToolResult.MatchRollupFragment(perFile));
        out.add(new TextFragment("Sample matches (one per file):"));
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Entry e : entries) {
            if (seen.size() >= topSnippets) {
                break;
            }
            if (seen.add(e.file())) {
                out.add(e.fragment());
            }
        }
        out.add(new TextFragment("Narrow with `path=…` or a tighter query for full line-level content."));
        return ToolResult.of(out);
    }

    private static String countHeader(int total, int files, int totalSymbols, int totalText) {
        return total + " matches across " + files + (files == 1 ? " file" : " files")
                + " (" + totalSymbols + " symbols, " + totalText + " text)";
    }

    /** Repo-relative display of a symbol/text source path; {@code external} when there is none. */
    private static String relFile(Path root, Path p) {
        if (p == null) {
            return "external";
        }
        Path abs = p.toAbsolutePath().normalize();
        try {
            return root.relativize(abs).toString();
        } catch (RuntimeException differentRoot) {
            return abs.toString();
        }
    }

    private static String relFileStr(Path root, String p) {
        return p == null ? "external" : relFile(root, Path.of(p));
    }
}
