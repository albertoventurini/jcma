package jcma.workspace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Task-02 — {@link Workspace} discovery: cp.txt parsing (the {@code SolverSetup} read loop) and
 * pom source-directory discovery. Pure file-parsing, no live mvn.
 */
class WorkspaceTest {

    private static final Path FIXTURES = Path.of("src/test/resources/fixtures/engine");

    // ---------------------------------------------------------------- cp.txt parsing

    @Test
    void readClasspathJarsSplitsAndKeepsOnlyJars(@TempDir Path dir) throws Exception {
        Path a = dir.resolve("a.jar");
        Path b = dir.resolve("b.jar");
        Path classesDir = dir.resolve("target/classes");
        // pathSeparator-joined, mixing jars with a non-jar classes dir that must be dropped.
        String cp = String.join(File.pathSeparator,
                a.toString(), classesDir.toString(), b.toString());
        Path cpFile = dir.resolve("cp.txt");
        Files.writeString(cpFile, cp);

        List<Path> jars = Workspace.readClasspathJars(cpFile);

        assertEquals(List.of(a, b), jars, "only .jar entries, in order; the classes dir dropped");
    }

    @Test
    void readClasspathJarsToleratesMissingFile(@TempDir Path dir) {
        List<Path> jars = Workspace.readClasspathJars(dir.resolve("does-not-exist.txt"));
        assertTrue(jars.isEmpty(), "a missing cp.txt yields an empty classpath, not an error");
    }

    @Test
    void readClasspathJarsToleratesEmptyFile(@TempDir Path dir) throws Exception {
        Path cpFile = dir.resolve("cp.txt");
        Files.writeString(cpFile, "  \n");
        assertTrue(Workspace.readClasspathJars(cpFile).isEmpty(), "blank cp.txt yields empty");
    }

    // ---------------------------------------------------------------- pom source-dir discovery

    @Test
    void discoverSourceRootsReadsExplicitSourceDirectory() {
        Path root = FIXTURES.resolve("ws-custom-srcdir");
        List<Path> roots = Workspace.discoverSourceRoots(root);
        assertEquals(List.of(root.resolve("java")), roots,
                "<sourceDirectory>java</sourceDirectory> overrides the default layout");
    }

    @Test
    void discoverSourceRootsDefaultsToStandardLayout() {
        Path root = FIXTURES.resolve("ws-standard");
        List<Path> roots = Workspace.discoverSourceRoots(root);
        assertEquals(List.of(root.resolve("src/main/java")), roots,
                "no <sourceDirectory> → Maven standard layout src/main/java");
    }
}
