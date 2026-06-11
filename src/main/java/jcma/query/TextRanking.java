package jcma.query;

import jcma.session.TextHit;

import java.util.Comparator;

/**
 * The relevance ordering for the {@code grep_java} text tier (M3 task-04) — the sibling of
 * {@link SymbolRanking}, promoted to a named, structure-independent business rule so it survives any
 * change to the underlying text segment and is applied to the merged base+overlay result.
 *
 * <p><b>Option B + diversity (locked 2026-06-11).</b> A <b>whole-word</b> match (a real token, bounded
 * by non-identifier chars) ranks above an incidental substring (so a line using the word {@code id}
 * outranks one that merely contains {@code valid}/{@code width}) — the rank-before-truncate win over a
 * raw grep cut. Within a tier, the deterministic {@code (file, line, col)} order keeps results stable
 * and, for the auto-collapse preview, lets the tool take a diverse <em>one-per-file</em> sample by
 * walking the already-ranked hits.
 */
public final class TextRanking {

    private TextRanking() {}

    /** A comparator ordering text hits by relevance: whole-word first, then {@code (file, line, col)}. */
    public static Comparator<TextHit> byRelevance() {
        return Comparator.comparing((TextHit h) -> !h.wholeWord())        // whole-word (false) sorts first
                .thenComparing(h -> h.file() == null ? "" : h.file())
                .thenComparingInt(TextHit::line)
                .thenComparingInt(TextHit::col);
    }
}
