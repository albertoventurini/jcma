package jcma.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import jcma.index.Csr;
import jcma.index.Csr.Edge;
import jcma.index.EdgeType;
import jcma.index.Moniker;
import jcma.index.Occurrence;
import jcma.index.Occurrence.Role;
import jcma.index.Range;
import jcma.index.Symbol;
import jcma.index.SymbolKind;
import jcma.index.SymbolStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Task 03 — the {@code jcma index-dump --symbols <indexDir>} manual-check surface, exercised on the
 * JVM through the same {@code Main.run} dispatch the native binary uses.
 */
class IndexDumpTest {

    private record Run(int exit, String out, String err) {}

    private static Run dispatch(String... args) {
        ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(outBuf, true, StandardCharsets.UTF_8);
        PrintStream err = new PrintStream(errBuf, true, StandardCharsets.UTF_8);
        int exit = Main.run(args, out, err);
        return new Run(exit, outBuf.toString(StandardCharsets.UTF_8), errBuf.toString(StandardCharsets.UTF_8));
    }

    @Test
    void dumpListsSymbolsAndMonikers(@TempDir Path dir) throws Exception {
        String type = Moniker.forType(Moniker.forPackage("com.acme"), "Greeter");
        String greet = Moniker.forMethod(type, "greet", List.of());
        SymbolStore.write(dir.resolve(SymbolStore.FILE_NAME), List.of(
                new Symbol(type, SymbolKind.CLASS, 0, null, 0, new Range(1, 1, 3, 1), "Greeter", null),
                new Symbol(greet, SymbolKind.METHOD, 0, type, 0, new Range(2, 3, 2, 20),
                        "greet", "java.lang.String greet()")));

        Run r = dispatch("index-dump", "--symbols", dir.toString());
        assertEquals(0, r.exit(), "dump should exit 0: " + r.out() + r.err());
        assertTrue(r.out().contains(greet), "dump lists monikers: " + r.out());
        assertTrue(r.out().contains("METHOD"), "dump lists kinds: " + r.out());
        assertTrue(r.out().contains("Greeter"), "dump lists names: " + r.out());
    }

    @Test
    void dumpEdgesListsFwdAndRevWithTypes(@TempDir Path dir) throws Exception {
        String type = Moniker.forType(Moniker.forPackage("com.acme"), "Greeter");
        String greet = Moniker.forMethod(type, "greet", List.of());
        String render = Moniker.forMethod(type, "render", List.of());
        SymbolStore.write(dir.resolve(SymbolStore.FILE_NAME), List.of(
                new Symbol(type, SymbolKind.CLASS, 0, null, 0, new Range(1, 1, 9, 1), "Greeter", null),
                new Symbol(greet, SymbolKind.METHOD, 0, type, 0, new Range(2, 3, 4, 3), "greet", null),
                new Symbol(render, SymbolKind.METHOD, 0, type, 0, new Range(5, 3, 7, 3), "render", null)));
        // ids are assigned by sorted moniker; resolve them so the edge endpoints are right.
        try (SymbolStore s = SymbolStore.load(dir.resolve(SymbolStore.FILE_NAME))) {
            int greetId = s.idOf(greet).getAsInt();
            int renderId = s.idOf(render).getAsInt();
            // greet() calls render() at line 3, enclosed by greet.
            Csr.write(dir.resolve(Csr.FILE_NAME), s.size(), List.of(
                    new Edge(greetId, renderId, EdgeType.CALLS,
                            new Occurrence(0, new Range(3, 5, 3, 13), greetId, Role.CALL))));
        }

        // fwd view from greet → render
        Run fwd = dispatch("index-dump", "--edges", dir.toString(), greet);
        assertEquals(0, fwd.exit(), "edges dump should exit 0: " + fwd.out() + fwd.err());
        assertTrue(fwd.out().contains("CALLS"), "fwd lists edge type: " + fwd.out());
        assertTrue(fwd.out().contains(render), "fwd lists the target moniker: " + fwd.out());

        // rev view from render ← greet (find-references direction)
        Run rev = dispatch("index-dump", "--edges", dir.toString(), render);
        assertEquals(0, rev.exit(), "edges dump should exit 0: " + rev.out() + rev.err());
        assertTrue(rev.out().contains("CALLS"), "rev lists edge type: " + rev.out());
        assertTrue(rev.out().contains(greet), "rev lists the referencing moniker: " + rev.out());
    }

    @Test
    void dumpEdgesUnknownMonikerExitsNonZero(@TempDir Path dir) throws Exception {
        SymbolStore.write(dir.resolve(SymbolStore.FILE_NAME), List.of(
                new Symbol("com/acme/Greeter#", SymbolKind.CLASS, 0, null, 0, new Range(1, 1, 2, 1), "Greeter", null)));
        Csr.write(dir.resolve(Csr.FILE_NAME), 1, List.of());
        Run r = dispatch("index-dump", "--edges", dir.toString(), "no/Such#thing.");
        assertNotEquals(0, r.exit(), "an unknown moniker should fail, not succeed silently");
    }

    @Test
    void dumpEdgesUsageErrorWithoutMoniker(@TempDir Path dir) {
        Run r = dispatch("index-dump", "--edges", dir.toString());
        assertEquals(2, r.exit(), "missing <moniker> is a usage error");
    }

    @Test
    void dumpMissingStoreExitsNonZero(@TempDir Path dir) {
        Run r = dispatch("index-dump", "--symbols", dir.toString());
        assertNotEquals(0, r.exit(), "a missing store should fail, not print nothing and succeed");
    }

    @Test
    void dumpUsageErrorWithoutSymbolsFlag(@TempDir Path dir) {
        Run r = dispatch("index-dump", dir.toString());
        assertEquals(2, r.exit(), "missing --symbols flag is a usage error");
    }
}
