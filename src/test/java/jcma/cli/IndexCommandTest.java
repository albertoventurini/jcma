package jcma.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;
import jcma.workspace.IndexLayout;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Task 06 · P4 — {@code jcma index [indexDir]}, the cold full-index command, through the same
 * {@code Main.run} dispatch the native binary uses. The repo is inferred from the working directory
 * ({@code -C <repo>} here); fixtures are copied into a {@code @TempDir} so the working-dir→project-root
 * walk-up can't escape into jcma's own Gradle project. The persisted index is then verified queryable
 * by {@code search} and {@code stats} (PRD §10 M1 verification).
 */
class IndexCommandTest {

    private static final Path REPO = Path.of("src/test/resources/fixtures/indexer");
    private static final Path ENGINE = Path.of("src/test/resources/fixtures/engine");

    private record Run(int exit, String out, String err) {}

    private static Run dispatch(String... args) {
        ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        int exit = Main.run(args, new PrintStream(outBuf, true, StandardCharsets.UTF_8),
                new PrintStream(errBuf, true, StandardCharsets.UTF_8));
        return new Run(exit, outBuf.toString(StandardCharsets.UTF_8), errBuf.toString(StandardCharsets.UTF_8));
    }

    @Test
    void indexBuildsAQueryablePersistedIndex(@TempDir Path repo, @TempDir Path indexDir) throws IOException {
        copyTree(REPO, repo);
        Run idx = dispatch("index", "-C", repo.toString(), indexDir.toString());
        assertEquals(0, idx.exit(), "index should exit 0: " + idx.out() + idx.err());
        assertTrue(idx.out().contains("symbols"), "reports a symbol count: " + idx.out());

        // The persisted index is real: search and stats read it back.
        Run search = dispatch("search", indexDir.toString(), "Circle");
        assertEquals(0, search.exit(), search.err());
        assertTrue(search.out().contains("Circle"), "search finds an indexed symbol: " + search.out());

        Run stats = dispatch("stats", indexDir.toString());
        assertEquals(0, stats.exit(), stats.err());
        assertTrue(stats.out().contains("base:"), stats.out());
    }

    @Test
    void indexDefaultsToTheUserCacheNotTheRepo(@TempDir Path repoCopy) throws IOException {
        Files.copy(REPO.resolve("com/example/shapes/Shape.java"), repoCopy.resolve("Shape.java"));
        // The default index lives under the user cache, keyed by the (temp) repo path — never in-repo.
        Path defaultIndex = IndexLayout.defaultIndexDir(repoCopy);
        try {
            Run idx = dispatch("index", "-C", repoCopy.toString());
            assertEquals(0, idx.exit(), idx.err());
            assertTrue(Files.isDirectory(defaultIndex),
                    "default index dir created under the user cache: " + defaultIndex);
            assertFalse(Files.exists(repoCopy.resolve(".jcma")),
                    "the repo is left clean — no in-repo .jcma dir to gitignore or get IDE-indexed");
        } finally {
            deleteRecursively(defaultIndex);
        }
    }

    @Test
    void indexesTestSourcesAndTagsThem(@TempDir Path repo, @TempDir Path indexDir) throws IOException {
        // ws-with-tests has src/main/java/com/example/Greeter.java + src/test/java/.../GreeterTest.java.
        copyTree(ENGINE.resolve("ws-with-tests"), repo);
        Run idx = dispatch("index", "-C", repo.toString(), indexDir.toString());
        assertEquals(0, idx.exit(), "index should exit 0: " + idx.out() + idx.err());

        Run search = dispatch("search", indexDir.toString(), "Greeter");
        assertEquals(0, search.exit(), search.err());
        assertTrue(search.out().contains("GreeterTest"),
                "test sources are indexed (GreeterTest found): " + search.out());
        assertTrue(search.out().contains("MAIN"), "the prod class is tagged MAIN: " + search.out());
        assertTrue(search.out().contains("TEST"), "the test class is tagged TEST: " + search.out());
    }

    @Test
    void adHocLayoutFallsBackToAllMain(@TempDir Path repo, @TempDir Path indexDir) throws IOException {
        // ws-adhoc has a loose Loose.java with no pom and no standard layout → repo-root, all MAIN.
        copyTree(ENGINE.resolve("ws-adhoc"), repo);
        Run idx = dispatch("index", "-C", repo.toString(), indexDir.toString());
        assertEquals(0, idx.exit(), "index should exit 0: " + idx.out() + idx.err());

        Run search = dispatch("search", indexDir.toString(), "Loose");
        assertEquals(0, search.exit(), search.err());
        assertTrue(search.out().contains("Loose"), "the loose class is indexed: " + search.out());
        assertTrue(search.out().contains("MAIN"), "ad-hoc sources are tagged MAIN: " + search.out());
    }

    @Test
    void warmReopenReportsZeroReparsedThenOneAfterAnEdit(@TempDir Path repo) throws IOException {
        // Task 08 manual check: index twice (second is warm, 0 reparsed); edit one file → 1 reparsed.
        Path src = repo.resolve("src/main/java/com/example");
        Files.createDirectories(src);
        Path foo = src.resolve("Foo.java");
        Files.writeString(foo, "package com.example;\npublic class Foo { public void f() {} }\n");
        Path indexDir = repo.resolve(".jcma");

        Run first = dispatch("index", "-C", repo.toString(), indexDir.toString());
        assertEquals(0, first.exit(), first.err());

        Run second = dispatch("index", "-C", repo.toString(), indexDir.toString());
        assertEquals(0, second.exit(), second.err());
        assertTrue(second.out().contains("0 reparsed"),
                "an unchanged warm reopen reports 0 reparsed: " + second.out());

        // Add a method (changes the file's size) → exactly one file re-parsed.
        Files.writeString(foo, "package com.example;\npublic class Foo { public void f() {} public void g() {} }\n");
        Run third = dispatch("index", "-C", repo.toString(), indexDir.toString());
        assertEquals(0, third.exit(), third.err());
        assertTrue(third.out().contains("1 reparsed"), "one edit → 1 reparsed: " + third.out());
    }

    @Test
    void usageWhenTooManyArgs() {
        // index now takes at most [indexDir]; a second positional is a usage error.
        assertEquals(2, dispatch("index", "a", "b").exit());
    }

    private static void copyTree(Path src, Path dst) throws IOException {
        try (Stream<Path> walk = Files.walk(src)) {
            for (Path p : (Iterable<Path>) walk::iterator) {
                Path target = dst.resolve(src.relativize(p).toString());
                if (Files.isDirectory(p)) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(p, target);
                }
            }
        }
    }

    /** Remove a cache dir created by a default-location test so the run leaves no trace in ~/.cache. */
    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : (Iterable<Path>) walk.sorted(Comparator.reverseOrder())::iterator) {
                Files.deleteIfExists(p);
            }
        }
    }
}
