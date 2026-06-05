package jcma.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import jcma.index.Csr.Edge;
import jcma.index.Occurrence.Role;

/**
 * Task 04 — the FFM columnar typed bidirectional {@link Csr}, the production form of M0
 * {@code SpikeD}'s CSR. Round-trips typed edges + occurrences, checks an occurrence carries its
 * enclosing symbol + range + role, and asserts {@code fwd}/{@code rev} match an in-memory oracle
 * over a random graph (ported from {@code SpikeD.Oracle}).
 */
class CsrTest {

    private static final String SEG = Csr.FILE_NAME;

    // A small hand-built graph over ids 0..3:
    //   0 --CONTAINS--> 1         (structural: no occurrence)
    //   1 --CALLS-----> 2         (call site in file 7, enclosed by 1)
    //   1 --REFERENCES-> 3        (read of a field in file 7, enclosed by 1)
    private static List<Edge> sampleEdges() {
        List<Edge> edges = new ArrayList<>();
        edges.add(new Edge(0, 1, EdgeType.CONTAINS, Occurrence.NONE));
        edges.add(new Edge(1, 2, EdgeType.CALLS,
                new Occurrence(7, new Range(10, 5, 10, 18), 1, Role.CALL)));
        edges.add(new Edge(1, 3, EdgeType.REFERENCES,
                new Occurrence(7, new Range(11, 9, 11, 14), 1, Role.READ)));
        return edges;
    }

    @Test
    void roundTripsTypedEdgesAndOccurrences(@TempDir Path dir) throws IOException {
        Path p = dir.resolve(SEG);
        Csr.write(p, 4, sampleEdges());

        CsrOracle oracle = new CsrOracle();
        sampleEdges().forEach(oracle::add);

        try (Csr csr = Csr.load(p)) {
            assertEquals(4, csr.symbolCount());
            assertEquals(3, csr.edgeCount());
            for (int id = 0; id < 4; id++) {
                assertEquals(oracle.fwd(id), Set.copyOf(csr.fwd(id)), "fwd edges of #" + id);
                assertEquals(oracle.rev(id), Set.copyOf(csr.rev(id)), "rev edges of #" + id);
            }
        }
    }

    @Test
    void occurrenceCarriesEnclosingAndRange(@TempDir Path dir) throws IOException {
        Path p = dir.resolve(SEG);
        Csr.write(p, 4, sampleEdges());
        try (Csr csr = Csr.load(p)) {
            // The call edge 1 -> 2 reached from the reverse (find-references) direction.
            List<Edge> into2 = csr.rev(2);
            assertEquals(1, into2.size(), "one reference into #2");
            Edge call = into2.get(0);
            assertEquals(1, call.src());
            assertEquals(EdgeType.CALLS, call.type());
            Occurrence occ = call.occurrence();
            assertEquals(7, occ.fileId(), "occurrence file");
            assertEquals(new Range(10, 5, 10, 18), occ.range(), "occurrence range");
            assertEquals(1, occ.enclosingSymbolId(), "occurrence is enclosed by #1");
            assertEquals(Role.CALL, occ.role());
        }
    }

    @Test
    void structuralEdgeHasNoOccurrence(@TempDir Path dir) throws IOException {
        Path p = dir.resolve(SEG);
        Csr.write(p, 4, sampleEdges());
        try (Csr csr = Csr.load(p)) {
            List<Edge> outOf0 = csr.fwd(0);
            assertEquals(1, outOf0.size());
            Edge contains = outOf0.get(0);
            assertEquals(EdgeType.CONTAINS, contains.type());
            assertTrue(contains.occurrence().isNone(), "CONTAINS carries Occurrence.NONE");
        }
    }

    @Test
    void isolatedSymbolHasEmptyAdjacency(@TempDir Path dir) throws IOException {
        Path p = dir.resolve(SEG);
        Csr.write(p, 4, sampleEdges());
        try (Csr csr = Csr.load(p)) {
            // #2 has an incoming edge but no outgoing; #0 the reverse; a never-touched id (would be
            // out of range here) — within range, fwd/rev of a sink/source are empty as expected.
            assertTrue(csr.fwd(2).isEmpty(), "#2 has no outgoing edges");
            assertTrue(csr.rev(0).isEmpty(), "#0 has no incoming edges");
        }
    }

    @Test
    void emptyGraphRoundTrips(@TempDir Path dir) throws IOException {
        Path p = dir.resolve(SEG);
        Csr.write(p, 3, List.of());
        try (Csr csr = Csr.load(p)) {
            assertEquals(3, csr.symbolCount());
            assertEquals(0, csr.edgeCount());
            for (int id = 0; id < 3; id++) {
                assertTrue(csr.fwd(id).isEmpty());
                assertTrue(csr.rev(id).isEmpty());
            }
        }
    }

    @Test
    void fwdRevMatchOracleOnRandomGraph(@TempDir Path dir) throws IOException {
        int n = 500;
        int m = 4000;
        Random rnd = new Random(42);
        EdgeType[] types = EdgeType.values();
        Role[] roles = Role.values();

        CsrOracle oracle = new CsrOracle();
        List<Edge> edges = new ArrayList<>(m);
        for (int i = 0; i < m; i++) {
            int s = rnd.nextInt(n);
            int d = rnd.nextInt(n);
            EdgeType t = types[rnd.nextInt(types.length)];
            // A distinct range per edge (line = i) makes every edge value-distinct, so set equality
            // against the oracle is exact and also round-trips m unique occurrences.
            Occurrence occ = new Occurrence(i % 13, new Range(i, 1, i, 7), s, roles[rnd.nextInt(roles.length)]);
            Edge e = new Edge(s, d, t, occ);
            edges.add(e);
            oracle.add(e);
        }

        Path p = dir.resolve(SEG);
        Csr.write(p, n, edges);
        try (Csr csr = Csr.load(p)) {
            assertEquals(n, csr.symbolCount());
            assertEquals(oracle.edgeCount(), csr.edgeCount());
            for (int id = 0; id < n; id++) {
                assertEquals(oracle.fwd(id), Set.copyOf(csr.fwd(id)), "fwd #" + id);
                assertEquals(oracle.rev(id), Set.copyOf(csr.rev(id)), "rev #" + id);
            }
        }
    }

    @Test
    void badMagicRejected(@TempDir Path dir) throws IOException {
        Path p = dir.resolve(SEG);
        Files.write(p, new byte[128]); // zero bytes: not our magic
        assertThrows(IOException.class, () -> Csr.load(p), "a bad magic must be rejected, not read");
    }

    @Test
    void rejectsOutOfRangeEndpoint(@TempDir Path dir) {
        Path p = dir.resolve(SEG);
        List<Edge> bad = List.of(new Edge(0, 9, EdgeType.CALLS, Occurrence.NONE));
        assertThrows(IllegalArgumentException.class, () -> Csr.write(p, 4, bad),
                "an endpoint id outside [0, symbolCount) must be rejected");
    }
}
