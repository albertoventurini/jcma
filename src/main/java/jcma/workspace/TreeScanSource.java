package jcma.workspace;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The M1 stand-in {@link FreshnessSource} (task-11c): an <b>O(tree) polling producer</b>. It walks the
 * source roots and reports the {@code .java} files whose {@code (size, mtime)} differ from the snapshot
 * it took at its last drain — created, deleted, or modified since. It plugs into the existing seam and
 * is drained by the {@code AnalysisSession}/{@code FreshnessGuard} before a query serves an answer.
 *
 * <p><b>Why a full scan is the correct M1 stand-in.</b> A whole-tree walk cannot miss an event, so the
 * "what changed" signal is <em>complete</em> — the only thing a real OS watcher (the deferred
 * task-09 Option 9) later improves is <em>latency</em> (it learns of a change without a walk). Because
 * the {@link FreshnessGuard} hash-confirms every reported path, this source is free to <em>over</em>-
 * report (an mtime-lie is reconciled to a no-op {@link NodeDiff}); it must only never <em>under</em>-
 * report, which a full {@code (size, mtime)} sweep guarantees.
 *
 * <p>Detection is deliberately decoupled from the {@link FileTable}: the snapshot is this source's own
 * baseline of disk state ("changed since I last looked"), so it needs no shared mutable index state and
 * can be swapped for a watcher without touching the guard.
 */
public final class TreeScanSource implements FreshnessSource {

    private final List<Path> roots;
    private Map<Path, long[]> snapshot; // abs path → {size, mtimeMillis}

    /**
     * A source over {@code roots}, taking its initial baseline snapshot now — so only files changed
     * <em>after</em> construction are reported. Roots that are not directories are skipped.
     */
    public TreeScanSource(List<Path> roots) {
        this.roots = List.copyOf(roots);
        this.snapshot = scan();
    }

    @Override
    public synchronized Set<Path> drainChanged() {
        Map<Path, long[]> current = scan();
        Set<Path> changed = new HashSet<>();
        for (Map.Entry<Path, long[]> e : current.entrySet()) {
            long[] prev = snapshot.get(e.getKey());
            if (prev == null || prev[0] != e.getValue()[0] || prev[1] != e.getValue()[1]) {
                changed.add(e.getKey()); // new or modified (size/mtime differ)
            }
        }
        for (Path gone : snapshot.keySet()) {
            if (!current.containsKey(gone)) {
                changed.add(gone); // deleted since last drain → guard tombstones it
            }
        }
        snapshot = current; // advance the baseline; each change is reported at most once
        return changed;
    }

    /** Walk the roots, recording every {@code .java} regular file's {@code (size, mtime)}. */
    private Map<Path, long[]> scan() {
        Map<Path, long[]> out = new HashMap<>();
        for (Path root : roots) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> walk = Files.walk(root)) {
                walk.filter(p -> p.toString().endsWith(".java"))
                        .filter(Files::isRegularFile)
                        .forEach(p -> {
                            Path abs = p.toAbsolutePath().normalize();
                            try {
                                out.put(abs, new long[] {
                                        Files.size(abs), Files.getLastModifiedTime(abs).toMillis()});
                            } catch (IOException e) {
                                // raced with a delete between walk and stat → simply omit it this pass
                            }
                        });
            } catch (IOException e) {
                throw new UncheckedIOException("tree scan failed under " + root, e);
            }
        }
        return out;
    }
}
