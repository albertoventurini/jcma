package jcma.engine;

import java.nio.file.Path;

/**
 * A resolved reference to a method/constructor-like declaration — the answer to "what does this
 * call site point at". A jcma-owned record so the {@link AnalysisEngine} seam never leaks a
 * JavaParser/SymbolSolver type (PRD §4; a javac-hybrid fallback must satisfy the same contract).
 *
 * <p>The declaration site is carried inside the ref (computed via {@code AssociableToAST.toAst()},
 * the M0 {@code SpikeA.locate()} approach). When the declaration lives in a dependency jar or the
 * JDK — i.e. there is no project source to point at — {@code declFile} is {@code null} and
 * {@code declLine}/{@code declCol} are {@code -1}; the {@code fqn}/{@code signature} are still resolved.
 *
 * @param fqn       fully-qualified name of the declaration (no parameter list)
 * @param signature fully-qualified signature including parameter types (e.g. {@code a.b.C.m(int)})
 * @param declFile  source file declaring the target, or {@code null} if external (jar/JDK)
 * @param declLine  1-based declaration start line, or {@code -1} if external/unknown
 * @param declCol   1-based declaration start column, or {@code -1} if external/unknown (pins the start
 *                  position so go-to-def attributes the moniker by column-precise containment)
 */
public record ResolvedRef(String fqn, String signature, Path declFile, int declLine, int declCol) {}
