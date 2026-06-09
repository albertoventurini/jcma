package jcma.resolve;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import jcma.IndexFixture;
import jcma.index.Symbol;
import jcma.obs.Metrics;
import jcma.workspace.Workspace;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A type used as the qualifier of a static-member access ({@code Holder.init()}) is a {@code NameExpr}
 * in value position. JavaSymbolSolver's value resolution rejects it with UnsolvedSymbolException,
 * which the {@code FailureClassifier} bucketed as {@code MISSING_CLASSPATH} — so the reference was
 * dropped into the unconfirmed tail instead of being confirmed. The name-as-type fallback in
 * {@code JavaParserEngine.attempt()} resolves it correctly; this pins that behavior.
 */
class StaticQualifierReferenceTest {

    private static final Path REPO = Path.of("src/test/resources/fixtures/resolve/static-qualifier");
    private static final String HOLDER = "app/Holder#";

    @Test
    void staticCallQualifierIsAConfirmedTypeReference(@TempDir Path indexDir) throws Exception {
        IndexFixture.build(REPO, indexDir);
        try (EdgeResolver resolver = EdgeResolver.open(indexDir, Workspace.ofSourceRoot(REPO), Metrics.create())) {
            Symbol holder = resolver.declarations("Holder").stream()
                    .filter(s -> HOLDER.equals(s.moniker()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("Holder type not indexed"));
            References refs = resolver.findReferences(holder);

            assertFalse(refs.hasUnconfirmedTail(),
                    "the `Holder.init()` qualifier resolves as a type — not a MISSING_CLASSPATH miss: "
                            + refs.unconfirmed());
            assertEquals(1, refs.totalRefs(), "the lone reference: the static-call qualifier `Holder`");
            assertEquals(1, refs.groups().size(), "one enclosing symbol");
            assertEquals("app/Caller#go().", refs.groups().get(0).enclosingMoniker(),
                    "enclosed by Caller.go()");
        }
    }
}
