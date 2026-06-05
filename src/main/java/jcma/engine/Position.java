package jcma.engine;

/**
 * A 1-based source position (line and column), matching the M0 worksheet and the {@code jcma
 * resolve <file> <line:col>} CLI surface. A jcma-owned value type — no JavaParser position type
 * crosses the {@link AnalysisEngine} seam (PRD §4).
 */
public record Position(int line, int col) {}
