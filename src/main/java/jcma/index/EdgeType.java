package jcma.index;

/**
 * The type of a directed edge in the §5.1 symbol graph (the {@code edge-type} of a {@link Csr}
 * adjacency entry). Stored in the columnar CSR as its {@link #ordinal()}, so the order is part of
 * the on-disk format: <b>append new types at the end, never reorder or remove</b> (an older
 * persisted index would otherwise read edge types shifted).
 *
 * <p>Edges are directed src → dst; the {@link Csr} keeps both directions so a query can walk
 * outgoing ({@code fwd}) or incoming ({@code rev}) neighbours. Roughly:
 * <ul>
 *   <li>{@link #CONTAINS} — structural nesting (type contains method/field; package contains type).
 *       The reverse of a symbol's {@code enclosing} column, materialised as a walkable edge.</li>
 *   <li>{@link #REFERENCES} — a name use (read/write of a field or variable, a type mention).</li>
 *   <li>{@link #CALLS} — a method-invocation site.</li>
 *   <li>{@link #EXTENDS} / {@link #IMPLEMENTS} / {@link #OVERRIDES} — the type/override hierarchy.</li>
 *   <li>{@link #HAS_TYPE} — a declaration's declared type (field/var/param/return).</li>
 *   <li>{@link #INSTANTIATES} — a {@code new} expression.</li>
 *   <li>{@link #ANNOTATED_BY} — an annotation application.</li>
 *   <li>{@link #THROWS} — a {@code throws} clause entry.</li>
 *   <li>{@link #IMPORTS} — an import of a type.</li>
 * </ul>
 */
public enum EdgeType {
    CONTAINS,
    REFERENCES,
    CALLS,
    EXTENDS,
    IMPLEMENTS,
    OVERRIDES,
    HAS_TYPE,
    INSTANTIATES,
    ANNOTATED_BY,
    THROWS,
    IMPORTS;

    private static final EdgeType[] VALUES = values();

    /** Reverse of {@link #ordinal()} for the CSR read path; rejects an out-of-range ordinal. */
    public static EdgeType byOrdinal(int ordinal) {
        if (ordinal < 0 || ordinal >= VALUES.length) {
            throw new IllegalArgumentException("bad EdgeType ordinal: " + ordinal);
        }
        return VALUES[ordinal];
    }
}
