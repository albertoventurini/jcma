package jcma.engine;

import java.nio.file.Path;

/**
 * A resolved type reference — the answer to "what type is named here". Sibling of
 * {@link ResolvedRef} for type occurrences (PRD §4). Declaration site is carried inside, with the
 * same external-target convention: {@code declFile == null} / {@code declLine == -1} when the type
 * is declared in a dependency jar or the JDK.
 *
 * @param fqn      fully-qualified name of the type
 * @param declFile source file declaring the type, or {@code null} if external (jar/JDK)
 * @param declLine 1-based declaration start line, or {@code -1} if external/unknown
 * @param declCol  1-based declaration start column, or {@code -1} if external/unknown (pins the start
 *                 position so go-to-def attributes the moniker by column-precise containment)
 */
public record ResolvedType(String fqn, Path declFile, int declLine, int declCol) {}
