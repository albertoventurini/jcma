package jcma.resolve;

import jcma.engine.AnalysisEngine;
import jcma.engine.ParsedUnit;
import jcma.engine.Position;
import jcma.engine.ResolvedHierarchy;
import jcma.engine.ResolvedOccurrence;
import jcma.engine.ResolvedRef;
import jcma.engine.ResolvedType;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * A test spy over a real {@link AnalysisEngine}: it delegates every call (so results are genuine) and
 * counts the two layer-resolution entry points the B0/B1 split is about — {@code resolveHierarchy} and
 * {@code resolveTypeReferences}. The find_references path must touch the hierarchy layer zero times
 * (B0); the hierarchy-query path must touch the type-ref layer zero times (B1's symmetric half).
 */
final class CountingEngine implements AnalysisEngine {

    private final AnalysisEngine delegate;
    int hierarchyCalls;
    int typeRefCalls;

    CountingEngine(AnalysisEngine delegate) {
        this.delegate = delegate;
    }

    @Override
    public ParsedUnit parse(Path file) throws IOException {
        return delegate.parse(file);
    }

    @Override
    public Optional<ResolvedRef> resolveMethodCall(ParsedUnit unit, Position pos) {
        return delegate.resolveMethodCall(unit, pos);
    }

    @Override
    public Optional<ResolvedType> resolveType(ParsedUnit unit, Position pos) {
        return delegate.resolveType(unit, pos);
    }

    @Override
    public List<ResolvedOccurrence> resolveOccurrences(ParsedUnit unit, String simpleName) {
        return delegate.resolveOccurrences(unit, simpleName);
    }

    @Override
    public List<ResolvedOccurrence> resolveTypeReferences(ParsedUnit unit, String simpleName) {
        typeRefCalls++;
        return delegate.resolveTypeReferences(unit, simpleName);
    }

    @Override
    public List<ResolvedHierarchy> resolveHierarchy(ParsedUnit unit) {
        hierarchyCalls++;
        return delegate.resolveHierarchy(unit);
    }

    @Override
    public void refresh() {
        delegate.refresh();
    }
}
