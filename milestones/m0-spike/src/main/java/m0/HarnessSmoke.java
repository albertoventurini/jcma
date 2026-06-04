package m0;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ParserConfiguration.LanguageLevel;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JarTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * M0 wiring smoke test (throwaway). Proves the JavaParser + JavaSymbolSolver stack wires up
 * against a real repo BEFORE the full Spike A harness: parse (JDK-25 level) + JDK/jar/source
 * type-solving + actually resolving a method call to its declaration.
 *
 * Not a gate, not a benchmark. Usage:
 *   java m0.HarnessSmoke <sourceRoot> <cp.txt>
 * e.g. corpus/jackson-databind/src/main/java  corpus/jackson-databind/cp.txt
 */
public final class HarnessSmoke {

    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            System.err.println("usage: HarnessSmoke <sourceRoot> <cp.txt>");
            System.exit(2);
        }
        Path sourceRoot = Path.of(args[0]);
        Path cpFile = Path.of(args[1]);

        // CombinedTypeSolver = JDK (reflection) + project source + every dependency jar.
        CombinedTypeSolver solver = new CombinedTypeSolver();
        solver.add(new ReflectionTypeSolver(false));
        solver.add(new JavaParserTypeSolver(sourceRoot));
        int jars = 0;
        if (Files.exists(cpFile)) {
            String cp = Files.readString(cpFile).trim();
            if (!cp.isEmpty()) {
                for (String entry : cp.split(java.io.File.pathSeparator)) {
                    entry = entry.trim();
                    if (entry.endsWith(".jar")) {
                        try {
                            solver.add(new JarTypeSolver(entry));
                            jars++;
                        } catch (IOException e) {
                            System.err.println("  skip jar (" + e.getMessage() + "): " + entry);
                        }
                    }
                }
            }
        }

        ParserConfiguration config = new ParserConfiguration()
                .setLanguageLevel(LanguageLevel.JAVA_25)
                .setSymbolResolver(new JavaSymbolSolver(solver));
        JavaParser parser = new JavaParser(config);

        System.out.printf("sourceRoot=%s%n", sourceRoot);
        System.out.printf("dependency jars on classpath: %d%n", jars);

        int filesParsed = 0, parseFailed = 0, calls = 0, resolved = 0, unresolved = 0;
        int samplesShown = 0;
        final int FILE_BUDGET = 60;   // smoke: a slice, not the whole repo (that's Spike A)

        try (Stream<Path> walk = Files.walk(sourceRoot)) {
            List<Path> files = walk
                    .filter(p -> p.toString().endsWith(".java"))
                    .limit(FILE_BUDGET)
                    .toList();

            for (Path f : files) {
                ParseResult<CompilationUnit> result;
                try {
                    result = parser.parse(f);
                } catch (IOException e) {
                    parseFailed++;
                    continue;
                }
                if (!result.isSuccessful() || result.getResult().isEmpty()) {
                    parseFailed++;
                    continue;
                }
                filesParsed++;
                CompilationUnit cu = result.getResult().get();
                for (MethodCallExpr call : cu.findAll(MethodCallExpr.class)) {
                    calls++;
                    try {
                        var decl = call.resolve();
                        resolved++;
                        if (samplesShown < 5) {
                            System.out.printf("  RESOLVED  %-22s -> %s%n",
                                    call.getNameAsString() + "()", decl.getQualifiedSignature());
                            samplesShown++;
                        }
                    } catch (RuntimeException e) {
                        unresolved++;
                    }
                }
            }
        }

        double rate = calls == 0 ? 0 : (100.0 * resolved / calls);
        System.out.printf("%nfiles parsed=%d (parse-failed=%d) over budget=%d%n",
                filesParsed, parseFailed, FILE_BUDGET);
        System.out.printf("method calls: total=%d resolved=%d unresolved=%d  (%.1f%% resolved)%n",
                calls, resolved, unresolved, rate);

        if (resolved == 0) {
            System.err.println("SMOKE FAIL: nothing resolved — wiring is broken");
            System.exit(1);
        }
        System.out.println("SMOKE OK");
    }
}
