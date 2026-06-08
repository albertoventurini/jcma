package jcma.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jcma.cli.Main;
import jcma.index.Symbol;
import jcma.obs.Metrics;
import jcma.resolve.ReferenceGroup;
import jcma.resolve.References;
import jcma.resolve.UnconfirmedRef;
import jcma.workspace.FreshnessSource;
import jcma.workspace.TreeScanSource;
import jcma.workspace.Workspace;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Task-11c (red-first) — the node-diff cascade through an {@link AnalysisSession}: a body edit leaves
 * cross-file cached refs untouched; an API edit (rename) returns <b>exactly</b> the referrer files to
 * unresolved via the reverse edges (no repo-wide, no non-referrer); adding a supertype makes a
 * previously-unconfirmed ref bind by cascading the hierarchy edge; the {@link TreeScanSource} and the
 * on-access backstop both trigger the cascade before a query serves an answer.
 *
 * <p>Each test builds a tiny {@code app}-package repo under {@code @TempDir} (so files can be edited
 * mid-session), cold-indexes it, then drives a single long-lived session across the edit — the model
 * the real long-running (MCP) process uses, which the one-shot CLI cannot exercise.
 */
class CascadeTest {

    // ------------------------------------------------------------------ fixtures / helpers

    private static Path app(Path repo) {
        return repo.resolve("app");
    }

    private static Path write(Path repo, String name, String content) throws IOException {
        Path dir = app(repo);
        Files.createDirectories(dir);
        Path f = dir.resolve(name);
        Files.writeString(f, content);
        return f;
    }

    /** Rewrite {@code file} and push its mtime forward, so freshness sees a genuine change. */
    private static void edit(Path file, String content) throws IOException {
        Files.writeString(file, content);
        long later = Files.getLastModifiedTime(file).toMillis() + 5_000;
        Files.setLastModifiedTime(file, FileTime.fromMillis(later));
    }

    private static Path index(Path repo) {
        Path indexDir = repo.resolve(".jcma");
        PrintStream sink = new PrintStream(OutputStream.nullOutputStream());
        assertEquals(0, Main.run(new String[] {"index", repo.toString(), indexDir.toString()}, sink, sink),
                "jcma index should succeed");
        return indexDir;
    }

    private static Symbol decl(AnalysisSession s, String moniker) throws IOException {
        String simple = moniker.substring(moniker.lastIndexOf('#') + 1);
        simple = simple.replaceAll("\\(.*", "").replace(".", "");
        for (Symbol sym : s.declarations(simple)) {
            if (sym.moniker().equals(moniker)) {
                return sym;
            }
        }
        throw new AssertionError("declaration not found: " + moniker);
    }

    private static boolean hasGroup(References refs, String enclosingMoniker) {
        return refs.groups().stream().anyMatch(g -> g.enclosingMoniker().equals(enclosingMoniker));
    }

    private static boolean tailHas(References refs, String fileName) {
        return refs.unconfirmed().stream().anyMatch(u -> u.file().getFileName().toString().equals(fileName));
    }

    private static boolean invalidated(AnalysisSession s, String fileName) {
        return s.invalidatedByLastRefresh().stream()
                .anyMatch(p -> p.getFileName().toString().equals(fileName));
    }

    // ------------------------------------------------------------------ 1. body edit — edit-locality

    @Test
    void bodyEditLeavesCrossFileRefsUntouched(@TempDir Path repo) throws IOException {
        write(repo, "Service.java", "package app; public class Service { public void run() { int x = 1; } }");
        write(repo, "Client.java", "package app; public class Client { void go() { new Service().run(); } }");
        Path service = app(repo).resolve("Service.java");
        Path indexDir = index(repo);

        try (AnalysisSession s = AnalysisSession.open(indexDir, Workspace.discover(repo), Metrics.create())) {
            Symbol run = decl(s, "app/Service#run().");
            assertTrue(hasGroup(s.findReferences(run), "app/Client#go()."), "Client.go references run() (warm)");

            edit(service, "package app; public class Service { public void run() { compute(); log(); } void compute() {} void log() {} }");

            References after = s.findReferences(run);
            assertTrue(s.invalidatedByLastRefresh().isEmpty(),
                    "a body edit changes no member identity → no referrer is returned to unresolved");
            assertTrue(hasGroup(after, "app/Client#go()."), "Client's cached reference to run() is intact");
        }
    }

    // ------------------------------------------------------------------ 2. API edit — exact cascade

    @Test
    void apiEditCascadesToReferrersOnly(@TempDir Path repo) throws IOException {
        write(repo, "Service.java", "package app; public class Service { public void run() {} }");
        write(repo, "Client.java", "package app; public class Client { void go() { new Service().run(); } }");
        write(repo, "Other.java", "package app; public class Other { void noop() { int y = 2; } }");
        Path service = app(repo).resolve("Service.java");
        Path indexDir = index(repo);

        try (AnalysisSession s = AnalysisSession.open(indexDir, Workspace.discover(repo), Metrics.create())) {
            Symbol run = decl(s, "app/Service#run().");
            assertTrue(hasGroup(s.findReferences(run), "app/Client#go()."), "Client references run() (warm)");

            edit(service, "package app; public class Service { public void execute() {} }"); // rename run → execute

            s.findReferences(run); // old target → on-access reindex of Service + cascade
            assertTrue(invalidated(s, "Client.java"), "the exact referrer (Client) is returned to unresolved");
            assertFalse(invalidated(s, "Other.java"), "a non-referrer is NOT invalidated (no repo-wide cascade)");
            assertEquals(1, s.invalidatedByLastRefresh().size(), "exactly one referrer cascaded");
        }
    }

    // ------------------------------------------------------------------ 3. supertype — completeness (11a/11b)

    @Test
    void addingSupertypeBindsAPreviouslyUnconfirmedRef(@TempDir Path repo) throws IOException {
        write(repo, "Base.java", "package app; public class Base { public void inherited() {} }");
        write(repo, "Service.java", "package app; public class Service { }");
        write(repo, "Client.java", "package app; public class Client { void go(Service s) { s.inherited(); } }");
        Path service = app(repo).resolve("Service.java");
        Path indexDir = index(repo);

        TreeScanSource source = new TreeScanSource(List.of(repo));
        try (AnalysisSession s = AnalysisSession.open(indexDir, Workspace.discover(repo), source, Metrics.create())) {
            Symbol inherited = decl(s, "app/Base#inherited().");
            References before = s.findReferences(inherited);
            assertFalse(hasGroup(before, "app/Client#go(Service)."), "Client.inherited() does not bind yet");
            assertTrue(tailHas(before, "Client.java"), "it is in the unconfirmed tail (Service has no inherited())");

            edit(service, "package app; public class Service extends Base { }"); // Service now inherits inherited()

            References after = s.findReferences(inherited); // scanner drains Service → hierarchy cascade
            assertTrue(invalidated(s, "Client.java"), "the supertype edit cascades to Client via rev(Service)");
            assertTrue(hasGroup(after, "app/Client#go(Service)."), "Client.inherited() now binds (tail → confirmed)");
            assertFalse(tailHas(after, "Client.java"), "and is no longer in the unconfirmed tail");
        }
    }

    // ------------------------------------------------------------------ 4. tree-scan source — live producer

    @Test
    void treeScanSourceTriggersCascadeForAnUnreadFile(@TempDir Path repo) throws IOException {
        write(repo, "Service.java", "package app; public class Service { public void run() {} }");
        write(repo, "Client.java", "package app; public class Client { void go() { new Service().run(); } }");
        write(repo, "Other.java", "package app; public class Other { void noop() {} }");
        Path service = app(repo).resolve("Service.java");
        Path indexDir = index(repo);

        TreeScanSource source = new TreeScanSource(List.of(repo));
        try (AnalysisSession s = AnalysisSession.open(indexDir, Workspace.discover(repo), source, Metrics.create())) {
            s.findReferences(decl(s, "app/Service#run().")); // warm Client's reference

            edit(service, "package app; public class Service { public void execute() {} }"); // rename run → execute

            // A query that does NOT read Service: the scanner must still surface the change + cascade.
            List<Symbol> execute = s.declarations("execute");
            assertFalse(execute.isEmpty(), "the scanner-driven reindex makes the renamed member queryable");
            assertTrue(invalidated(s, "Client.java"), "the scanner triggered the cascade to Service's referrer");
        }
    }

    // ------------------------------------------------------------------ 5. validate-on-read floor (no event)

    @Test
    void onAccessBackstopCatchesEditWithNoProducerEvent(@TempDir Path repo) throws IOException {
        write(repo, "Service.java", "package app; public class Service { public void run() {} }");
        write(repo, "Client.java", "package app; public class Client { void go() { new Service().run(); } }");
        Path service = app(repo).resolve("Service.java");
        Path indexDir = index(repo);

        // No proactive producer: only the on-access backstop can catch the edit.
        try (AnalysisSession s = AnalysisSession.open(indexDir, Workspace.discover(repo), FreshnessSource.none(), Metrics.create())) {
            Symbol run = decl(s, "app/Service#run().");
            assertTrue(hasGroup(s.findReferences(run), "app/Client#go()."), "Client references run() (warm)");

            edit(service, "package app; public class Service { public void execute() {} }"); // rename, no event

            References after = s.findReferences(run); // reads Service → on-access reindex + cascade
            assertTrue(invalidated(s, "Client.java"), "the on-access backstop forced the reindex + cascade");
            assertFalse(hasGroup(after, "app/Client#go()."), "Client's stale binding to run() is gone");
            assertTrue(tailHas(after, "Client.java"), "Client's now-broken call surfaces as unconfirmed");
        }
    }
}
