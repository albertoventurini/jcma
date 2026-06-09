package jcma.engine;

import java.nio.file.Path;

/**
 * The declaration a use-site resolved to (the edge's {@code dst}) — engine-neutral facts only. The
 * declaration site ({@code declFile}:{@code declLine}:{@code declCol}, via {@code AssociableToAST.toAst()})
 * is how {@code jcma.resolve} bridges back to the graph node's moniker; when the target lives in a
 * jar/JDK (no project source) {@code declFile} is {@code null} and {@code declLine}/{@code declCol} are
 * {@code -1}, and the {@code fqn}/{@code signature}/{@code kind} drive a phantom node instead.
 *
 * @param fqn       fully-qualified name (no parameter list), best-effort
 * @param signature human-readable signature (FQN + params), or the FQN when the kind has none
 * @param declFile  declaring source file, or {@code null} if external (jar/JDK)
 * @param declLine  1-based declaration start line, or {@code -1} if external/unknown
 * @param declCol   1-based declaration start column, or {@code -1} if external/unknown; with
 *                  {@code declLine} it pins the start position so the moniker bridge attributes by
 *                  column-precise containment (else a same-line member shadows its enclosing type)
 * @param kind      the resolved declaration's kind
 */
public record ResolvedTarget(String fqn, String signature, Path declFile, int declLine, int declCol,
        DeclKind kind) {}
