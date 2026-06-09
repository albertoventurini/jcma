package jcma.cli;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;
import jcma.workspace.IndexLayout;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The CLI infers the repo from the working directory instead of a {@code <repo>} positional: a bare
 * {@code jcma index} / {@code jcma refs <symbol>} acts on the project you're sitting in, and an
 * explicit {@code -C <dir>} (git-style, before or after the subcommand) overrides the working dir.
 * The working dir is resolved <b>up to the project root</b> so any subdir keys the same index.
 *
 * <p>Driven through {@code Main.run} (the dispatch the native binary uses); fixtures are copied into a
 * {@code @TempDir} so the walk-up can't escape into jcma's own Gradle project.
 */
class CliWorkingDirTest {

    private static final Path FIXTURE = Path.of("src/test/resources/fixtures/indexer");

    private record Run(int exit, String out, String err) {}

    private static Run dispatch(String... args) {
        ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        int exit = Main.run(args, new PrintStream(outBuf, true, UTF_8), new PrintStream(errBuf, true, UTF_8));
        return new Run(exit, outBuf.toString(UTF_8), errBuf.toString(UTF_8));
    }

    @Test
    void indexInfersTheRepoFromMinusC(@TempDir Path repo) throws IOException {
        copyTree(FIXTURE, repo);
        Path indexDir = IndexLayout.defaultIndexDir(repo);
        try {
            Run idx = dispatch("index", "-C", repo.toString());
            assertEquals(0, idx.exit(), "index -C <repo> builds without a <repo> positional: " + idx.err());
            assertTrue(Files.isDirectory(indexDir), "the default cache index was built: " + indexDir);
        } finally {
            deleteRecursively(indexDir);
        }
    }

    @Test
    void minusCIsRecognizedBeforeTheSubcommand(@TempDir Path repo) throws IOException {
        copyTree(FIXTURE, repo);
        Path indexDir = IndexLayout.defaultIndexDir(repo);
        try {
            assertEquals(0, dispatch("index", "-C", repo.toString()).exit(), "build the index first");
            // refs takes <symbol> only now — the repo comes from -C, placed before the subcommand.
            Run refs = dispatch("-C", repo.toString(), "refs", "Circle");
            assertEquals(0, refs.exit(), "`-C <repo> refs <symbol>` resolves against the inferred repo: " + refs.err());
        } finally {
            deleteRecursively(indexDir);
        }
    }

    @Test
    void workingDirResolvesUpToProjectRootSoAnySubdirKeysTheSameIndex(@TempDir Path root) throws IOException {
        Files.writeString(root.resolve("settings.gradle.kts"), "");          // a project-root marker
        Path pkg = root.resolve("src/main/java/com/example");
        Files.createDirectories(pkg);
        Files.writeString(pkg.resolve("Foo.java"),
                "package com.example;\npublic class Foo { public void f() {} }\n");
        Path subdir = root.resolve("src/main/java");
        Path indexAtRoot = IndexLayout.defaultIndexDir(root);
        try {
            // Run from a subdir; the index must still be keyed to the project root, not the subdir.
            Run idx = dispatch("index", "-C", subdir.toString());
            assertEquals(0, idx.exit(), idx.err());
            assertTrue(Files.isDirectory(indexAtRoot),
                    "a subdir working dir resolves up to the project root: " + indexAtRoot);
        } finally {
            deleteRecursively(indexAtRoot);
        }
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
