package jcma.tools;

import jcma.engine.Position;
import jcma.index.Symbol;
import jcma.mcp.ToolHandler;
import jcma.mcp.json.JsonValue;
import jcma.mcp.json.JsonValue.JsonObject;
import jcma.query.QueryService;
import jcma.query.QueryTimeoutException;
import jcma.resolve.Hierarchy;
import jcma.response.BudgetPolicy;
import jcma.response.Shaping;
import jcma.response.ToolResult;
import jcma.response.ToolResult.Fragment;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * The {@code find_subtypes} §6 tool (M2 task-05): the <b>transitive</b> subtype/implementor/overrider
 * closure of a type or method — {@code find_subtypes(Base)} returns the whole closure {@code {B, C, D}},
 * not just the direct neighbour, each row carrying its {@code file:line} and a {@code depth}/edge-kind
 * relationship label. Same two input modes as the other §6 tools (by symbol with optional qualified
 * filter, or by position). A method target works for free — {@code OVERRIDES} is in the hierarchy edge
 * set, so {@code find_subtypes(A#m())} returns the transitive overriders. Routed through the {@link
 * BudgetPolicy} (the header count is sacred; trailing nodes are elastic).
 */
public final class FindSubtypesTool implements ToolHandler {

    private final Supplier<QueryService> svc;
    private final BudgetPolicy budget;

    public FindSubtypesTool(Supplier<QueryService> svc, BudgetPolicy budget) {
        this.svc = svc;
        this.budget = budget;
    }

    @Override
    public String name() {
        return "find_java_subtypes";
    }

    @Override
    public String description() {
        return "List the transitive subtypes of a Java type (every descendant class/implementor) or the "
                + "transitive overriders of a method — resolved semantically, each with its file:line and "
                + "depth/edge-kind. By name (`Foo` or qualified) or by file:line:col.";
    }

    @Override
    public JsonValue schema() {
        return ToolSupport.symbolOrPositionSchema();
    }

    @Override
    public ToolResult call(JsonValue args) {
        JsonObject in = ToolSupport.obj(args);
        String symbol = in.optString("symbol");
        String file = in.optString("file");
        Integer line = ToolSupport.optInt(in, "line");
        Integer col = ToolSupport.optInt(in, "col");
        boolean symbolMode = symbol != null && !symbol.isBlank();
        boolean posMode = file != null && line != null && col != null;
        if (symbolMode == posMode) {
            return ToolResult.error("find_java_subtypes: provide either {symbol} or {file, line, col}");
        }
        try {
            QueryService q = svc.get();
            if (posMode) {
                Hierarchy.Result res =
                        q.subtypesTransitiveAt(Path.of(file), new Position(line, col), ToolSupport.DEFAULT_DEADLINE);
                return budget.apply(name(), ToolResult.of(Shaping.hierarchy("Subtypes", null, res)));
            }
            List<Symbol> targets = q.resolveTargets(symbol, ToolSupport.DEFAULT_DEADLINE);
            if (targets.isEmpty()) {
                return ToolResult.error("no declaration named '" + symbol + "'");
            }
            List<Fragment> out = new ArrayList<>();
            for (Symbol t : targets) {
                Hierarchy.Result res = q.subtypesTransitive(t, ToolSupport.DEFAULT_DEADLINE);
                out.addAll(Shaping.hierarchy("Subtypes", display(t), res));
            }
            return budget.apply(name(), ToolResult.of(out));
        } catch (IOException | QueryTimeoutException e) {
            return ToolResult.error("find_java_subtypes failed: " + e.getMessage());
        }
    }

    /** A target's display: its signature, else its moniker (the always-present identity). */
    private static String display(Symbol s) {
        return s.signature() != null ? s.signature() : s.moniker();
    }
}
