package jcma.resolve;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jcma.IndexFixture;
import jcma.engine.Position;
import jcma.index.Symbol;
import jcma.obs.Metrics;
import jcma.workspace.Workspace;

import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Task-10 (red-first) — {@code find_definition} in both PRD §6 input modes over the {@code
 * resolve/refs} fixture: by symbol (declaration lookup) and by use-site position (go-to-def). Both
 * are shaped with a signature + a context snippet (ported M0 {@code describe}/{@code locate}/{@code
 * snippetOf}).
 */
class FindDefinitionTest {

    private static final Path REFS = Path.of("src/test/resources/fixtures/resolve/refs");

    @Test
    void bySymbolReturnsTheDeclarationSiteAndSnippet(@TempDir Path indexDir) throws Exception {
        index(REFS, indexDir);
        try (EdgeResolver resolver = EdgeResolver.open(indexDir, Workspace.ofSourceRoot(REFS), Metrics.create())) {
            Symbol run = resolver.declarations("run").stream()
                    .filter(s -> "app/Service#run().".equals(s.moniker()))
                    .findFirst().orElseThrow();

            Definition def = resolver.findDefinition(run);

            assertTrue(def.file().toString().endsWith("Service.java"), "declared in Service.java");
            assertEquals(6, def.line(), "the run() declaration line");
            assertTrue(def.snippet().contains("run"), "context snippet of the declaration: " + def.snippet());
        }
    }

    @Test
    void byUseSitePositionResolvesToTheTargetDeclaration(@TempDir Path indexDir) throws Exception {
        index(REFS, indexDir);
        try (EdgeResolver resolver = EdgeResolver.open(indexDir, Workspace.ofSourceRoot(REFS), Metrics.create())) {
            // ClientA.java:7  ->  new Service().run();   (cursor inside the run() call)
            Optional<Definition> def =
                    resolver.findDefinitionAt(REFS.resolve("app/ClientA.java"), new Position(7, 24));

            assertTrue(def.isPresent(), "the call resolves");
            assertTrue(def.get().file().toString().endsWith("Service.java"), "go-to-def lands in Service.java");
            assertEquals(6, def.get().line(), "at the run() declaration");
        }
    }

    private static void index(Path repo, Path indexDir) {
        IndexFixture.build(repo, indexDir);
    }
}
