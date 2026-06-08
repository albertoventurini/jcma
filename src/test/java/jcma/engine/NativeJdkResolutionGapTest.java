package jcma.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ParserConfiguration.LanguageLevel;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.resolution.TypeSolver;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.javaparsermodel.JavaParserFacade;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JarTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;

import jcma.jdkindex.JdkIndexer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Parity probe for {@code docs/native-jdk-resolution-gap.md} — the "Cheap &amp; faithful" repro
 * (variant a) that <b>did not</b> reproduce the gap, and the load-bearing evidence for why.
 *
 * <p>The doc's hypothesis: the JDK type-solver differs by runtime — the JVM dev path uses
 * {@code ReflectionTypeSolver} (live host classes), native-image uses {@code JarTypeSolver} over the
 * byte-indexed, de-moduled host-JDK jar (Task-02b) — and on a repo with <b>no dependency jars</b>
 * that solver choice is the <em>only</em> engine-level difference between the runtimes, so swapping it
 * on the JVM should reproduce native's miss on the minimal 2-file {@code new Service().run()}.
 *
 * <p><b>It does not.</b> Built with the same {@link JavaParserEngine} solver stack but the JDK served
 * by {@code JarTypeSolver} over a fresh in-process {@link JdkIndexer} jar (the same indexer
 * {@code HostJdkIndex} caches for the native binary), the call resolves <em>identically</em> to the
 * reflection path — same {@code app.Service.run()}. So the gap is <b>not</b> the bare
 * {@code JarTypeSolver}-vs-{@code ReflectionTypeSolver} choice; it is native-runtime-specific (how
 * {@code JarTypeSolver}/javassist behaves under native-image), and must be chased with variant (b),
 * the {@code nativeTest} suite. This test stays as a regression guard that the JVM jar path keeps
 * reflection parity for this case — if it ever goes red, the gap has reached the JVM.
 */
class NativeJdkResolutionGapTest {

    private static final String SERVICE =
            "package app;\npublic class Service { public void run() { int x = 1; } }\n";
    private static final String CLIENT =
            "package app;\npublic class Client { void go() { new Service().run(); } }\n";

    @Test
    void hostJdkJarResolvesMethodCallLikeReflection(@TempDir Path repo, @TempDir Path jarDir)
            throws Exception {
        Files.createDirectories(repo.resolve("app"));
        Files.writeString(repo.resolve("app/Service.java"), SERVICE);
        Path client = repo.resolve("app/Client.java");
        Files.writeString(client, CLIENT);

        // Baseline — the JVM dev path: JDK via reflection.
        Optional<String> viaReflection = resolveRun(new ReflectionTypeSolver(false), repo, client);
        assertTrue(viaReflection.isPresent(),
                "reflection resolves new Service().run() (the JVM dev baseline)");

        // Native's JDK solver, emulated on the JVM: JarTypeSolver over the byte-indexed host-JDK jar —
        // the exact native substitute for reflection (Task-02b), produced in-process by the same
        // indexer HostJdkIndex caches for the native binary.
        Path jdkJar = jarDir.resolve("host-jdk.jar");
        JdkIndexer.main(new String[] {jdkJar.toString()});
        Optional<String> viaHostJdkJar = resolveRun(new JarTypeSolver(jdkJar), repo, client);

        // Parity: the jar path resolves the SAME declaration as reflection. The native gap therefore
        // does NOT live in the bare solver choice (variant a does not reproduce) — it is
        // native-runtime-specific and must be chased with variant (b)/nativeTest. If this ever fails,
        // the gap has reached the JVM jar path.
        assertEquals(viaReflection, viaHostJdkJar,
                "host-JDK jar must resolve new Service().run() identically to reflection (JVM parity)");
    }

    /**
     * Build {@link JavaParserEngine}'s solver stack ({@code CombinedTypeSolver} = JDK + source root,
     * {@code LanguageLevel.RAW}, {@code JavaSymbolSolver}) with the given JDK solver, then guard-resolve
     * the {@code run()} call in {@code file}. Returns the resolved qualified signature, or empty on any
     * failure — mirroring the engine's safe-degrade. The JDK solver is the only variable across calls.
     */
    private static Optional<String> resolveRun(TypeSolver jdkSolver, Path repo, Path file)
            throws Exception {
        JavaParserFacade.clearInstances(); // isolate from any sibling solver's cached resolutions
        CombinedTypeSolver solver = new CombinedTypeSolver();
        solver.add(jdkSolver);
        solver.add(new JavaParserTypeSolver(repo));
        ParserConfiguration config = new ParserConfiguration()
                .setLanguageLevel(LanguageLevel.RAW)
                .setSymbolResolver(new JavaSymbolSolver(solver));
        CompilationUnit cu = new JavaParser(config).parse(file).getResult().orElseThrow();
        MethodCallExpr run = cu.findAll(MethodCallExpr.class).stream()
                .filter(m -> m.getNameAsString().equals("run"))
                .findFirst().orElseThrow();
        try {
            return Optional.of(run.resolve().getQualifiedSignature());
        } catch (Throwable t) {
            return Optional.empty();
        }
    }
}
