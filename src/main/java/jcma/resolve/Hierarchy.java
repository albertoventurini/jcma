package jcma.resolve;

import jcma.index.EdgeType;
import jcma.index.MonikerEdge;
import jcma.index.Symbol;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The <b>transitive</b> type-hierarchy walk (M2 task-05) — the {@code find_supertypes} /
 * {@code find_subtypes} closure over the direct {@code EXTENDS}/{@code IMPLEMENTS}/{@code OVERRIDES}
 * primitives. Owned by / constructed from {@link EdgeResolver} (it leans on the resolver's private
 * symbol caches + lazy structural warm), kept separate so {@code EdgeResolver} stays focused on the
 * single-hop primitives.
 *
 * <p><b>BFS</b> so each node's recorded {@code depth} is the shortest hop count. The walk warms each
 * node's hierarchy neighbourhood <em>on demand</em> as it is dequeued (the structural layer is
 * lazy-resolved Tier-2): a subtype file references its supertype's simple name, so it becomes a
 * candidate at every level — one warm per level, not one up front. Supertypes walk the {@code fwd}
 * edges (a node's own file), subtypes the {@code rev} edges (the subtype files).
 *
 * <p><b>Termination.</b> A {@code visited} set of monikers makes the walk cycle-safe (Java forbids
 * inheritance cycles, but a stale/edge-case graph cycle must still terminate). External/JDK phantom
 * nodes (e.g. {@code Object}, {@code Serializable}) have no outgoing hierarchy edges in our repo-only
 * index, so the walk naturally stops there — they are still <b>returned</b>, marked external. A
 * reached-node cap ({@link #MAX_NODES}) bounds the closure; hitting it sets {@link Result#truncated()}.
 */
public final class Hierarchy {

    /**
     * Default reached-node cap (PRD §11): unbounded depth, but stop after this many reached nodes and
     * mark the result truncated. Calibrated to comfortably cover real hierarchies while bounding a
     * pathological fan-out; the output-token {@code BudgetPolicy} cap is separate and still applies.
     */
    public static final int MAX_NODES = 500;

    private final EdgeResolver resolver;

    Hierarchy(EdgeResolver resolver) {
        this.resolver = resolver;
    }

    /** The transitive supertype closure of {@code start} (walks {@code fwd}), capped at {@code maxNodes}. */
    Result supertypes(Symbol start, int maxNodes) {
        return walk(start, maxNodes, true);
    }

    /** The transitive subtype/overrider closure of {@code start} (walks {@code rev}), capped at {@code maxNodes}. */
    Result subtypes(Symbol start, int maxNodes) {
        return walk(start, maxNodes, false);
    }

    private Result walk(Symbol start, int maxNodes, boolean up) {
        List<HierarchyNode> out = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        visited.add(start.moniker());
        Deque<Hop> queue = new ArrayDeque<>();
        queue.add(new Hop(start.moniker(), 0));
        boolean truncated = false;

        while (!queue.isEmpty() && !truncated) {
            Hop hop = queue.poll();
            // Warm this node's neighbourhood before reading its edges (Tier-2 lazy resolve). A phantom
            // (no in-repo symbol) has no outgoing hierarchy edges, so there is nothing to warm.
            Symbol s = resolver.symbolFor(hop.moniker());
            if (s != null) {
                resolver.warmHierarchyNeighborhood(s);
            }
            for (MonikerEdge e : sortedEdges(hop.moniker(), up)) {
                String neighbour = up ? e.dst() : e.src();
                if (visited.contains(neighbour)) {
                    continue;
                }
                if (out.size() >= maxNodes) {
                    truncated = true;
                    break;
                }
                visited.add(neighbour);
                int depth = hop.depth() + 1;
                out.add(resolver.hierarchyNode(neighbour, depth, e.type()));
                queue.add(new Hop(neighbour, depth));
            }
        }

        // Stable display order: depth (BFS shells) then moniker — also makes the truncated subset
        // deterministic.
        out.sort(Comparator.comparingInt(HierarchyNode::depth).thenComparing(HierarchyNode::moniker));
        return new Result(out, truncated);
    }

    /** The hierarchy edges out of / into {@code moniker}, deterministically ordered by neighbour then type. */
    private List<MonikerEdge> sortedEdges(String moniker, boolean up) {
        List<MonikerEdge> edges = new ArrayList<>(
                up ? resolver.fwdHierarchy(moniker) : resolver.revHierarchy(moniker));
        edges.sort(Comparator.comparing((MonikerEdge e) -> up ? e.dst() : e.src())
                .thenComparingInt(e -> e.type().ordinal()));
        return edges;
    }

    /** A frontier entry: the node's moniker and its BFS depth from the start. */
    private record Hop(String moniker, int depth) {}

    /**
     * The walk's outcome: the closure nodes (depth-then-name ordered) and whether the node cap fired
     * (so the answer is explicitly <b>not</b> exhaustive — surfaced in the shaped header).
     */
    public record Result(List<HierarchyNode> nodes, boolean truncated) {

        public Result {
            nodes = List.copyOf(nodes);
        }

        /** The empty closure (an unresolved position-mode site / external-only target). */
        public static Result empty() {
            return new Result(List.of(), false);
        }
    }
}
