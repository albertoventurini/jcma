package jcma.resolve;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jcma.IndexFixture;
import jcma.index.Symbol;
import jcma.obs.Metrics;
import jcma.workspace.Workspace;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Regression — {@code find_references} on a <b>single-line record</b> used as a type. The record's
 * components share the declaration line with the header, so {@code monikerAt} must attribute a type
 * reference to the record <em>type</em> ({@code app/Coord#}), not to a component field whose span is
 * smaller. Before the fix, every {@code Coord} type reference was mis-keyed onto the first component,
 * so {@code rev(app/Coord#)} was empty and {@code find_references(Coord)} reported zero.
 */
class RecordTypeReferenceTest {

    private static final Path FIXTURE = Path.of("src/test/resources/fixtures/resolve/record-refs");

    @Test
    void typeReferencesToASingleLineRecordResolveToTheRecordType(@TempDir Path indexDir) throws Exception {
        index(FIXTURE, indexDir);
        try (EdgeResolver resolver = EdgeResolver.open(indexDir, Workspace.ofSourceRoot(FIXTURE), Metrics.create())) {
            Symbol target = resolver.declarations("Coord").stream()
                    .filter(s -> "app/Coord#".equals(s.moniker()))
                    .findFirst().orElseThrow(() -> new AssertionError("Coord record not indexed"));

            References refs = resolver.findReferences(target);

            // first(Coord): return type + parameter type (2); second(): local var type (1).
            assertEquals(3, refs.totalRefs(),
                    "type refs to the record: first()'s return+param + second()'s local");
            assertEquals(2, countFor(refs, "app/UsesCoord#first(Coord)."));
            assertEquals(1, countFor(refs, "app/UsesCoord#second()."));
            assertTrue(monikersOf(refs).stream().noneMatch(m -> m.startsWith("app/Coord#")),
                    "references must be keyed by the referrer, and the record must not self-reference a component");
        }
    }

    private static int countFor(References refs, String enclosingMoniker) {
        return refs.groups().stream()
                .filter(g -> enclosingMoniker.equals(g.enclosingMoniker()))
                .mapToInt(ReferenceGroup::count)
                .sum();
    }

    private static List<String> monikersOf(References refs) {
        return refs.groups().stream().map(ReferenceGroup::enclosingMoniker).toList();
    }

    private static void index(Path repo, Path indexDir) {
        IndexFixture.build(repo, indexDir);
    }
}
