package jcma.index;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The typed bidirectional symbol graph (PRD §5.1 "both edge directions"), the production form of M0
 * {@code SpikeD}'s CSR. Edges are directed {@code src → dst} over the {@code int32} symbol ids of a
 * {@link SymbolStore}; each edge carries an {@link EdgeType} and an {@link Occurrence} (its use
 * site — {@link Occurrence#NONE} for a structural edge). Stored as <b>compressed sparse rows</b> in
 * both directions — a per-symbol {@code offset[]} into a flat, src-sorted edge column block
 * ({@code fwd}), plus a second {@code offset[]} and an index permutation grouping the same edges by
 * {@code dst} ({@code rev}) so occurrences are stored once and reached from either end.
 *
 * <p>Read/written via FFM ({@code Arena.ofShared()} + {@code FileChannel.map} → {@code
 * MemorySegment}); the file is mmap'd and walked in place — no deserialisation. Mirrors
 * {@link SymbolStore}'s {@code write(Path,…)} / {@code load(Path)} shape.
 *
 * <p><b>Segment layout:</b> a {@value #HEADER_BYTES}-byte header (magic, version, symbol count
 * {@code n}, edge count {@code m}, section offsets), then {@code fwdOffset[n+1]}, the {@code m}-row
 * edge columns in src-sorted order ({@code src, dst, type, occFileId, occRange×4, occEnclosing,
 * occRole}), {@code revOffset[n+1]}, and {@code revEdge[m]} (indices into the edge rows, grouped by
 * dst).
 */
public final class Csr implements AutoCloseable {

    /** File name of the edge segment within an index directory (used by {@code jcma index-dump}). */
    public static final String FILE_NAME = "edges.seg";

    private static final ValueLayout.OfInt I = ValueLayout.JAVA_INT_UNALIGNED;
    private static final ValueLayout.OfLong L = ValueLayout.JAVA_LONG_UNALIGNED;
    private static final long MAGIC = 0x4A434D4143535231L; // "JCMACSR1"
    private static final int VERSION = 1;
    static final int HEADER_BYTES = 64;

    // Per-edge columns (each is m int32s), in the src-sorted edge block.
    private static final int E_SRC = 0;
    private static final int E_DST = 1;
    private static final int E_TYPE = 2;
    private static final int E_OCC_FILE = 3;
    private static final int E_OCC_START_LINE = 4;
    private static final int E_OCC_START_COL = 5;
    private static final int E_OCC_END_LINE = 6;
    private static final int E_OCC_END_COL = 7;
    private static final int E_OCC_ENCLOSING = 8;
    private static final int E_OCC_ROLE = 9;
    private static final int NUM_EDGE_COLS = 10;

    /**
     * One directed, typed edge with its use site. Returned by {@link #fwd}/{@link #rev} fully
     * populated (both endpoints, type, occurrence), so an edge from either direction is value-equal.
     *
     * @param src        source symbol id
     * @param dst        target symbol id
     * @param type       edge type; never {@code null}
     * @param occurrence the use site, or {@link Occurrence#NONE} for a structural edge; never {@code null}
     */
    public record Edge(int src, int dst, EdgeType type, Occurrence occurrence) {
        public Edge {
            Objects.requireNonNull(type, "edge type");
            Objects.requireNonNull(occurrence, "edge occurrence");
        }
    }

    private final Arena arena;
    private final MemorySegment seg;
    private final int n;
    private final int m;
    private final long fwdOffOff;
    private final long edgeColOff;
    private final long revOffOff;
    private final long revEdgeOff;

    private Csr(Arena arena, MemorySegment seg) {
        this.arena = arena;
        this.seg = seg;
        this.n = seg.get(I, 12);
        this.m = seg.get(I, 16);
        this.fwdOffOff = seg.get(L, 24);
        this.edgeColOff = seg.get(L, 32);
        this.revOffOff = seg.get(L, 40);
        this.revEdgeOff = seg.get(L, 48);
    }

    /**
     * Write the graph over {@code [0, symbolCount)} to {@code path} as a fresh segment (overwriting
     * any existing file). Every edge endpoint must be a valid id in {@code [0, symbolCount)}.
     */
    public static void write(Path path, int symbolCount, List<Edge> edges) throws IOException {
        if (symbolCount < 0) {
            throw new IllegalArgumentException("symbolCount must be >= 0: " + symbolCount);
        }
        int n = symbolCount;
        int m = edges.size();
        for (Edge e : edges) {
            if (e.src() < 0 || e.src() >= n || e.dst() < 0 || e.dst() >= n) {
                throw new IllegalArgumentException(
                        "edge endpoint outside [0," + n + "): " + e.src() + "->" + e.dst());
            }
        }

        // fwd CSR: bucket edges by src (stable within a bucket = input order).
        int[] fwdOffset = new int[n + 1];
        for (Edge e : edges) {
            fwdOffset[e.src() + 1]++;
        }
        for (int i = 0; i < n; i++) {
            fwdOffset[i + 1] += fwdOffset[i];
        }
        // Materialise the src-sorted edge rows.
        int[] cursor = fwdOffset.clone();
        int[][] cols = new int[NUM_EDGE_COLS][m];
        for (Edge e : edges) {
            int row = cursor[e.src()]++;
            Occurrence o = e.occurrence();
            Range r = o.range();
            cols[E_SRC][row] = e.src();
            cols[E_DST][row] = e.dst();
            cols[E_TYPE][row] = e.type().ordinal();
            cols[E_OCC_FILE][row] = o.fileId();
            cols[E_OCC_START_LINE][row] = r.startLine();
            cols[E_OCC_START_COL][row] = r.startCol();
            cols[E_OCC_END_LINE][row] = r.endLine();
            cols[E_OCC_END_COL][row] = r.endCol();
            cols[E_OCC_ENCLOSING][row] = o.enclosingSymbolId();
            cols[E_OCC_ROLE][row] = o.role().ordinal();
        }

        // rev CSR: bucket edge rows by dst; revEdge holds the row index (into the src-sorted block).
        int[] revOffset = new int[n + 1];
        for (int row = 0; row < m; row++) {
            revOffset[cols[E_DST][row] + 1]++;
        }
        for (int i = 0; i < n; i++) {
            revOffset[i + 1] += revOffset[i];
        }
        int[] revCursor = revOffset.clone();
        int[] revEdge = new int[m];
        for (int row = 0; row < m; row++) {
            revEdge[revCursor[cols[E_DST][row]]++] = row;
        }

        long fwdOffOff = HEADER_BYTES;
        long edgeColOff = fwdOffOff + (long) (n + 1) * 4;
        long revOffOff = edgeColOff + (long) NUM_EDGE_COLS * m * 4;
        long revEdgeOff = revOffOff + (long) (n + 1) * 4;
        long total = revEdgeOff + (long) m * 4;

        try (FileChannel ch = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.READ,
                StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
                Arena a = Arena.ofShared()) {
            MemorySegment seg = ch.map(FileChannel.MapMode.READ_WRITE, 0, total, a);
            seg.set(L, 0, MAGIC);
            seg.set(I, 8, VERSION);
            seg.set(I, 12, n);
            seg.set(I, 16, m);
            seg.set(L, 24, fwdOffOff);
            seg.set(L, 32, edgeColOff);
            seg.set(L, 40, revOffOff);
            seg.set(L, 48, revEdgeOff);
            writeInts(seg, fwdOffOff, fwdOffset);
            for (int c = 0; c < NUM_EDGE_COLS; c++) {
                writeInts(seg, edgeColOff + (long) c * m * 4, cols[c]);
            }
            writeInts(seg, revOffOff, revOffset);
            writeInts(seg, revEdgeOff, revEdge);
            seg.force();
        }
    }

    /** Memory-map an existing segment at {@code path}; throws {@link IOException} on a bad/short magic. */
    public static Csr load(Path path) throws IOException {
        long size = Files.size(path);
        Arena a = Arena.ofShared();
        boolean ok = false;
        try (FileChannel ch = FileChannel.open(path, StandardOpenOption.READ)) {
            if (size < HEADER_BYTES) {
                throw new IOException("edge store too small (" + size + " bytes): " + path);
            }
            MemorySegment seg = ch.map(FileChannel.MapMode.READ_ONLY, 0, size, a);
            if (seg.get(L, 0) != MAGIC) {
                throw new IOException("bad magic — not a jcma edge store: " + path);
            }
            int version = seg.get(I, 8);
            if (version != VERSION) {
                throw new IOException("unsupported edge-store version " + version + ": " + path);
            }
            Csr csr = new Csr(a, seg);
            ok = true;
            return csr;
        } finally {
            if (!ok) {
                a.close();
            }
        }
    }

    /** Number of symbol ids the graph is defined over (valid ids are {@code 0 .. symbolCount()-1}). */
    public int symbolCount() {
        return n;
    }

    /** Total number of directed edges. */
    public int edgeCount() {
        return m;
    }

    /** Outgoing edges of {@code symbolId} (those with {@code src == symbolId}), in stored order. */
    public List<Edge> fwd(int symbolId) {
        checkId(symbolId);
        int start = seg.get(I, fwdOffOff + (long) symbolId * 4);
        int end = seg.get(I, fwdOffOff + (long) (symbolId + 1) * 4);
        List<Edge> out = new ArrayList<>(end - start);
        for (int row = start; row < end; row++) {
            out.add(edgeAt(row));
        }
        return out;
    }

    /** Incoming edges of {@code symbolId} (those with {@code dst == symbolId}), in stored order. */
    public List<Edge> rev(int symbolId) {
        checkId(symbolId);
        int start = seg.get(I, revOffOff + (long) symbolId * 4);
        int end = seg.get(I, revOffOff + (long) (symbolId + 1) * 4);
        List<Edge> out = new ArrayList<>(end - start);
        for (int j = start; j < end; j++) {
            int row = seg.get(I, revEdgeOff + (long) j * 4);
            out.add(edgeAt(row));
        }
        return out;
    }

    @Override
    public void close() {
        arena.close();
    }

    private void checkId(int symbolId) {
        if (symbolId < 0 || symbolId >= n) {
            throw new IndexOutOfBoundsException("symbol id " + symbolId + " not in [0," + n + ")");
        }
    }

    /** Reconstruct the full {@link Edge} at edge row {@code row} (src-sorted block). */
    private Edge edgeAt(int row) {
        int src = col(E_SRC, row);
        int dst = col(E_DST, row);
        EdgeType type = EdgeType.byOrdinal(col(E_TYPE, row));
        Occurrence.Role role = Occurrence.Role.byOrdinal(col(E_OCC_ROLE, row));
        Range range = new Range(col(E_OCC_START_LINE, row), col(E_OCC_START_COL, row),
                col(E_OCC_END_LINE, row), col(E_OCC_END_COL, row));
        Occurrence occ = new Occurrence(col(E_OCC_FILE, row), range, col(E_OCC_ENCLOSING, row), role);
        return new Edge(src, dst, type, occ);
    }

    private int col(int c, int row) {
        return seg.get(I, edgeColOff + ((long) c * m + row) * 4);
    }

    private static void writeInts(MemorySegment seg, long off, int[] a) {
        for (int i = 0; i < a.length; i++) {
            seg.set(I, off + (long) i * 4, a[i]);
        }
    }
}
