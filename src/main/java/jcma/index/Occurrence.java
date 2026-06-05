package jcma.index;

/**
 * A concrete <em>use site</em> of a symbol in source (PRD §5.1 occurrence record) — the payload an
 * edge carries. Where {@link Symbol} is the declaration, an {@code Occurrence} is one place that
 * declaration is referenced: the file + range of the use, the enclosing symbol that contains it
 * (so a reference can be reported as "called from {@code Foo.bar}"), and the {@link Role} the use
 * plays. {@code find_references} returns these.
 *
 * <p>Structural edges (e.g. {@link EdgeType#CONTAINS}) have no use site; they carry {@link #NONE}.
 *
 * @param fileId           file the use occurs in, or {@code -1} for no site (structural edge)
 * @param range            source range of the use, or {@link Range#NONE}
 * @param enclosingSymbolId id of the symbol lexically containing the use, or {@code -1} if none
 * @param role             what the use does (see {@link Role}); never {@code null}
 */
public record Occurrence(int fileId, Range range, int enclosingSymbolId, Role role) {

    /** What a use site does — the four semantic roles, plus {@link #NONE} for a structural edge. */
    public enum Role {
        /** Reads a field / variable value. */
        READ,
        /** Writes (assigns) a field / variable. */
        WRITE,
        /** Invokes a method / constructor. */
        CALL,
        /** Mentions a type (extends/implements, declared type, cast, annotation, throws, …). */
        TYPEREF,
        /** No use-site role — the carrying edge is structural (e.g. {@link EdgeType#CONTAINS}). */
        NONE;

        private static final Role[] VALUES = values();

        /** Reverse of {@link #ordinal()} for the CSR read path; rejects an out-of-range ordinal. */
        public static Role byOrdinal(int ordinal) {
            if (ordinal < 0 || ordinal >= VALUES.length) {
                throw new IllegalArgumentException("bad Occurrence.Role ordinal: " + ordinal);
            }
            return VALUES[ordinal];
        }
    }

    /** Sentinel for "no use site" — carried by a structural edge ({@code fileId == -1}, {@link Role#NONE}). */
    public static final Occurrence NONE = new Occurrence(-1, Range.NONE, -1, Role.NONE);

    public Occurrence {
        if (range == null) {
            throw new IllegalArgumentException("occurrence range must not be null (use Range.NONE)");
        }
        if (role == null) {
            throw new IllegalArgumentException("occurrence role must not be null (use Role.NONE)");
        }
    }

    /** True if this is the structural {@link #NONE} sentinel (no use site). */
    public boolean isNone() {
        return fileId == -1 && role == Role.NONE && range.isNone();
    }
}
