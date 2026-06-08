package jcma.workspace;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Task-11c (red-first) — the {@link TreeScanSource} polling producer: it reports a {@code .java} file
 * whose bytes change after the source's baseline snapshot, drains each change at most once, and
 * surfaces deletions. The guard hash-confirms whatever it reports, so over-reporting is harmless; the
 * property under test is that a real change is never missed and is reported exactly once.
 */
class TreeScanSourceTest {

    private static Path write(Path dir, String name, String content) throws IOException {
        Files.createDirectories(dir);
        Path f = dir.resolve(name);
        Files.writeString(f, content);
        return f;
    }

    private static void edit(Path file, String content) throws IOException {
        Files.writeString(file, content);
        long later = Files.getLastModifiedTime(file).toMillis() + 5_000;
        Files.setLastModifiedTime(file, FileTime.fromMillis(later));
    }

    @Test
    void reportsAModifiedFileExactlyOnce(@TempDir Path root) throws IOException {
        Path src = root.resolve("app");
        Path a = write(src, "A.java", "package app; class A {}");
        write(src, "B.java", "package app; class B {}");

        TreeScanSource source = new TreeScanSource(List.of(root)); // baseline = current disk
        assertTrue(source.drainChanged().isEmpty(), "nothing changed since the baseline");

        edit(a, "package app; class A { void m() {} }");

        Set<Path> changed = source.drainChanged();
        assertTrue(changed.contains(a.toAbsolutePath().normalize()), "the modified file is reported: " + changed);
        assertFalse(source.drainChanged().contains(a.toAbsolutePath().normalize()),
                "draining clears it — reported at most once");
    }

    @Test
    void reportsADeletedFile(@TempDir Path root) throws IOException {
        Path src = root.resolve("app");
        Path a = write(src, "A.java", "package app; class A {}");

        TreeScanSource source = new TreeScanSource(List.of(root));
        Files.delete(a);

        assertTrue(source.drainChanged().contains(a.toAbsolutePath().normalize()),
                "a file removed since the baseline is reported (the guard tombstones it)");
    }
}
