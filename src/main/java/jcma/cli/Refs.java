package jcma.cli;

import jcma.index.Symbol;
import jcma.obs.Metrics;
import jcma.query.QueryService;
import jcma.query.QueryTimeoutException;
import jcma.resolve.Ref;
import jcma.resolve.ReferenceGroup;
import jcma.resolve.References;
import jcma.resolve.UnconfirmedRef;
import jcma.session.AnalysisSession;
import jcma.workspace.Workspace;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * {@code jcma refs <repo> <symbol> [--deadline <ms>]} (task-10; time-boxed in task-12) — Tier-2
 * find-references: resolve-on-demand, then print confirmed references <b>grouped by enclosing
 * symbol</b> with counts, plus the mandatory <b>unconfirmed tail</b>. The query is served through a
 * {@link QueryService} under {@code --deadline} (a clean timeout if exceeded). The index lives at
 * {@code <repo>/.jcma} (build it with {@code jcma index}).
 */
final class Refs {

    private Refs() {}

    static int run(String[] args, PrintStream out, PrintStream err) {
        Deadline.Parsed parsed = Deadline.parse(args);
        if (parsed.error() != null) {
            err.println("jcma: " + parsed.error());
            return 2;
        }
        String[] a = parsed.positional();
        if (a.length != 3) {
            err.println("jcma: usage: jcma refs <repo> <symbol> [--deadline <ms>]");
            return 2;
        }
        Path repo = Path.of(a[1]);
        String symbol = a[2];
        Duration deadline = parsed.deadline();
        Path indexDir = repo.resolve(".jcma");
        if (!Files.isDirectory(indexDir)) {
            err.println("jcma: no index at " + indexDir + " — run `jcma index " + repo + "` first");
            return 1;
        }
        try (QueryService svc = new QueryService(
                AnalysisSession.open(indexDir, Workspace.discover(repo), Metrics.noop()))) {
            List<Symbol> targets = svc.declarations(symbol, deadline);
            if (targets.isEmpty()) {
                err.println("jcma: no declaration named '" + symbol + "' in the index");
                return 1;
            }
            for (Symbol target : targets) {
                print(out, target, svc.findReferences(target, deadline));
            }
            return 0;
        } catch (QueryTimeoutException te) {
            err.println("jcma: " + te.getMessage());
            return 1;
        } catch (Exception e) {
            err.println("jcma: refs failed: " + e.getMessage());
            return 1;
        }
    }

    private static void print(PrintStream out, Symbol target, References refs) {
        out.printf("%nreferences to %s  [%s]%n", display(target), target.moniker());
        out.printf("  %d reference(s) in %d enclosing symbol(s)%n", refs.totalRefs(), refs.groups().size());
        for (ReferenceGroup g : refs.groups()) {
            out.printf("  %s  (%d)%n", g.enclosingSignature(), g.count());
            for (Ref r : g.refs()) {
                out.printf("    %s:%d  %s%n", r.file().getFileName(), r.range().startLine(), r.snippet());
            }
        }
        if (refs.hasUnconfirmedTail()) {
            out.printf("  unconfirmed (%d) — could not be resolved, so this set is not exhaustive:%n",
                    refs.unconfirmed().size());
            for (UnconfirmedRef u : refs.unconfirmed()) {
                out.printf("    %s:%d  %s  [%s]%n",
                        u.file().getFileName(), u.range().startLine(), u.snippet(), u.cause());
            }
        }
    }

    private static String display(Symbol s) {
        return s.signature() != null ? s.signature() : s.moniker();
    }
}
