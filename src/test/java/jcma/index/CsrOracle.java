package jcma.index;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jcma.index.Csr.Edge;

/**
 * In-memory ground truth for {@link CsrTest} — the production form of M0 {@code SpikeD.Oracle},
 * adapted from moniker space to {@code int32} id space and from untyped neighbour sets to typed
 * {@link Edge}s carrying occurrences. Maintains forward/reverse adjacency so the test loop can
 * assert {@code Csr.fwd/rev} against it cheaply (O(degree)).
 */
final class CsrOracle {

    private final Map<Integer, List<Edge>> fwdAdj = new HashMap<>();
    private final Map<Integer, List<Edge>> revAdj = new HashMap<>();
    private int edgeCount;

    void add(Edge e) {
        fwdAdj.computeIfAbsent(e.src(), k -> new ArrayList<>()).add(e);
        revAdj.computeIfAbsent(e.dst(), k -> new ArrayList<>()).add(e);
        edgeCount++;
    }

    /** Outgoing edges of {@code id} as a set (order-independent comparison; edges are made distinct). */
    Set<Edge> fwd(int id) {
        return new HashSet<>(fwdAdj.getOrDefault(id, List.of()));
    }

    /** Incoming edges of {@code id} as a set. */
    Set<Edge> rev(int id) {
        return new HashSet<>(revAdj.getOrDefault(id, List.of()));
    }

    int edgeCount() {
        return edgeCount;
    }
}
