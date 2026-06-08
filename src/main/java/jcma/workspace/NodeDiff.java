package jcma.workspace;

import jcma.index.Symbol;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The result of re-indexing one file (M1 task-11c) — the <b>Tier-1 node diff</b> that drives the
 * cascade. It is the structural delta between a file's <em>old</em> and <em>new</em> declaration
 * sets, expressed in moniker space:
 * <ul>
 *   <li>{@link #removed} — monikers that were declared and now are not (a delete, a rename's old
 *       name, a signature change that re-keys the moniker);</li>
 *   <li>{@link #added} — monikers that are newly declared (a new member, a rename's new name);</li>
 *   <li>{@link #signatureChanged} — monikers present in <em>both</em> whose {@link Symbol#signature()}
 *       differs (e.g. a return-type or modifier change that keeps the moniker stable).</li>
 * </ul>
 *
 * <p>This is the exact, member-granular signal task-11 replaces the old API-fingerprint heuristic
 * with: for each entry the cascade walks {@code store.rev(moniker)} (confirmed referrers) and, by
 * simple name, {@code store.rev(name~UNRESOLVED)} (unconfirmed referrers) to find the precise files
 * to return to unresolved. A <b>body edit</b> keeps every member's moniker and signature, so the diff
 * is empty and no referrer is touched — edit-locality falls out structurally.
 *
 * <p>{@link #reindexed} records whether the re-index applied a store edit at all (a re-parse or a
 * tombstone), distinct from {@link #hasNodeChanges()}: a body edit is {@code reindexed=true} with no
 * node changes; an unchanged / mtime-lie file is {@code reindexed=false}. Hierarchy-edge changes
 * (a new {@code EXTENDS}/{@code IMPLEMENTS}/{@code OVERRIDES}) are <em>not</em> visible at Tier-1 —
 * they are added by the Tier-2 {@code Cascade} after it re-resolves the changed file.
 */
public record NodeDiff(
        int fileId,
        boolean reindexed,
        Set<String> removed,
        Set<String> added,
        Set<String> signatureChanged) {

    public NodeDiff {
        removed = Set.copyOf(removed);
        added = Set.copyOf(added);
        signatureChanged = Set.copyOf(signatureChanged);
    }

    /** An untracked file (no {@link FileTable} row): nothing to re-index, no diff. */
    public static NodeDiff untracked() {
        return new NodeDiff(-1, false, Set.of(), Set.of(), Set.of());
    }

    /** {@code fileId} was checked and found unchanged (fast path or mtime-lie): no store edit, no diff. */
    public static NodeDiff unchanged(int fileId) {
        return new NodeDiff(fileId, false, Set.of(), Set.of(), Set.of());
    }

    /** A tombstoned (deleted) file: every previously-declared moniker is removed. */
    public static NodeDiff tombstoned(int fileId, List<Symbol> oldSymbols) {
        return new NodeDiff(fileId, true, monikers(oldSymbols), Set.of(), Set.of());
    }

    /** The diff between a re-parsed file's {@code oldSymbols} and {@code newSymbols}. */
    public static NodeDiff of(int fileId, List<Symbol> oldSymbols, List<Symbol> newSymbols) {
        Map<String, String> oldSig = signatures(oldSymbols);
        Map<String, String> newSig = signatures(newSymbols);
        Set<String> removed = new HashSet<>(oldSig.keySet());
        removed.removeAll(newSig.keySet());
        Set<String> added = new HashSet<>(newSig.keySet());
        added.removeAll(oldSig.keySet());
        Set<String> signatureChanged = new HashSet<>();
        for (Map.Entry<String, String> e : oldSig.entrySet()) {
            String now = newSig.get(e.getKey());
            if (now != null && !java.util.Objects.equals(e.getValue(), now)) {
                signatureChanged.add(e.getKey());
            }
        }
        return new NodeDiff(fileId, true, removed, added, signatureChanged);
    }

    /** True if any node was removed, added, or had its signature changed (the cascade trigger). */
    public boolean hasNodeChanges() {
        return !removed.isEmpty() || !added.isEmpty() || !signatureChanged.isEmpty();
    }

    /** The union {@code removed ∪ added ∪ signatureChanged} — the monikers whose referrers the cascade re-validates. */
    public Set<String> changedMonikers() {
        Set<String> all = new HashSet<>(removed);
        all.addAll(added);
        all.addAll(signatureChanged);
        return all;
    }

    private static Set<String> monikers(List<Symbol> symbols) {
        Set<String> out = new HashSet<>();
        for (Symbol s : symbols) {
            out.add(s.moniker());
        }
        return out;
    }

    private static Map<String, String> signatures(List<Symbol> symbols) {
        Map<String, String> out = new HashMap<>();
        for (Symbol s : symbols) {
            out.put(s.moniker(), s.signature());
        }
        return out;
    }
}
