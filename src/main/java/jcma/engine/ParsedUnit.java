package jcma.engine;

import com.github.javaparser.ast.CompilationUnit;
import java.nio.file.Path;

/**
 * An opaque jcma handle to a parsed source file. Deliberately <em>not</em> a record: the wrapped
 * JavaParser {@link CompilationUnit} must not cross the {@link AnalysisEngine} seam (PRD §4), so its
 * accessor is package-private — only {@link JavaParserEngine} in this package reaches it. The
 * source {@link #file()} is public so callers can report it.
 */
public final class ParsedUnit {

    private final CompilationUnit cu;
    private final Path file;

    ParsedUnit(CompilationUnit cu, Path file) {
        this.cu = cu;
        this.file = file;
    }

    /** Package-private — the engine impl's only door back to the JavaParser AST. */
    CompilationUnit cu() {
        return cu;
    }

    /** The source file this unit was parsed from. */
    public Path file() {
        return file;
    }
}
