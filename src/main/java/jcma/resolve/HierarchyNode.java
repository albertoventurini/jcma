package jcma.resolve;

import jcma.index.EdgeType;
import jcma.index.SymbolKind;

import java.nio.file.Path;

/**
 * One node of a transitive type-hierarchy walk (M2 task-05; the {@code find_supertypes} /
 * {@code find_subtypes} answers) — a sibling of {@link Definition} in {@code jcma.resolve}. Carries
 * everything shaping needs without re-querying: the node's identity + display, its declaration site,
 * and its <b>relationship to the start</b> — the shortest-hop {@code depth} (BFS) and the {@code via}
 * edge by which it was reached ({@code EXTENDS}/{@code IMPLEMENTS}/{@code OVERRIDES}).
 *
 * <p>An external/JDK node (a phantom supertype like {@code Object}, declared nowhere we parse) carries
 * {@code file == null} / {@code line == -1}; its {@code signature}/{@code moniker} are still populated.
 *
 * @param moniker   the node's stable moniker
 * @param signature human-readable display (signature, else the moniker with a {@code ~} marker stripped)
 * @param kind      the node's {@link SymbolKind} ({@code UNKNOWN} for a phantom)
 * @param file      declaring source file, or {@code null} if external
 * @param line      1-based declaration line, or {@code -1} if external/unknown
 * @param depth     shortest hop count from the start node (1 = a direct neighbour)
 * @param via       the hierarchy edge type by which this node was reached
 */
public record HierarchyNode(String moniker, String signature, SymbolKind kind, Path file, int line,
        int depth, EdgeType via) {}
