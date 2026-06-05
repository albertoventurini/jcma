package jcma.cli;

import jcma.index.Csr;
import jcma.index.Csr.Edge;
import jcma.index.Occurrence;
import jcma.index.Symbol;
import jcma.index.SymbolStore;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.OptionalInt;

/**
 * {@code jcma index-dump} — the index verification surface (PRD §9). Two views over a persisted
 * index directory:
 * <ul>
 *   <li>{@code --symbols <indexDir>} (task 03) — list every symbol's id, moniker, kind, name, site.</li>
 *   <li>{@code --edges <indexDir> <moniker>} (task 04) — print a symbol's forward and reverse
 *       neighbours with edge types + occurrence sites.</li>
 * </ul>
 * Dev-only eyeballing of the columnar store; the same {@code Main.run} dispatch runs under native.
 */
final class IndexDump {

    private IndexDump() {}

    /** Dispatch {@code index-dump} args; return the process exit code (0 ok, 1 failure, 2 usage). */
    static int run(String[] args, PrintStream out, PrintStream err) {
        if (args.length >= 2 && "--symbols".equals(args[1])) {
            return symbols(args, out, err);
        }
        if (args.length >= 2 && "--edges".equals(args[1])) {
            return edges(args, out, err);
        }
        usage(err);
        return 2;
    }

    // --- index-dump --symbols <indexDir> ------------------------------------------------------

    private static int symbols(String[] args, PrintStream out, PrintStream err) {
        if (args.length != 3) {
            usage(err);
            return 2;
        }
        Path seg = Path.of(args[2]).resolve(SymbolStore.FILE_NAME);
        if (!Files.exists(seg)) {
            err.println("jcma: no symbol store at " + seg);
            return 1;
        }
        try (SymbolStore store = SymbolStore.load(seg)) {
            out.println("symbols: " + store.size() + "  (" + seg + ")");
            for (int id = 0; id < store.size(); id++) {
                Symbol s = store.symbol(id);
                out.printf("#%-5d %-12s %-48s %-20s %s%s%n",
                        id, s.kind(), s.moniker(), s.name(), site(s),
                        s.signature() == null ? "" : "  " + s.signature());
            }
            return 0;
        } catch (Exception e) {
            err.println("jcma: index-dump failed: " + e.getMessage());
            return 1;
        }
    }

    // --- index-dump --edges <indexDir> <moniker> ----------------------------------------------

    private static int edges(String[] args, PrintStream out, PrintStream err) {
        if (args.length != 4) {
            usage(err);
            return 2;
        }
        Path indexDir = Path.of(args[2]);
        String moniker = args[3];
        Path symSeg = indexDir.resolve(SymbolStore.FILE_NAME);
        Path edgeSeg = indexDir.resolve(Csr.FILE_NAME);
        if (!Files.exists(symSeg)) {
            err.println("jcma: no symbol store at " + symSeg);
            return 1;
        }
        if (!Files.exists(edgeSeg)) {
            err.println("jcma: no edge store at " + edgeSeg);
            return 1;
        }
        try (SymbolStore symbols = SymbolStore.load(symSeg); Csr csr = Csr.load(edgeSeg)) {
            OptionalInt id = symbols.idOf(moniker);
            if (id.isEmpty()) {
                err.println("jcma: no symbol with moniker " + moniker);
                return 1;
            }
            int sid = id.getAsInt();
            out.println("edges for #" + sid + "  " + moniker);

            List<Edge> fwd = csr.fwd(sid);
            out.println("  fwd (outgoing, " + fwd.size() + "):");
            for (Edge e : fwd) {
                out.printf("    -[%s]-> #%d %s%s%n",
                        e.type(), e.dst(), monikerOf(symbols, e.dst()), occ(e.occurrence(), symbols));
            }

            List<Edge> rev = csr.rev(sid);
            out.println("  rev (incoming, " + rev.size() + "):");
            for (Edge e : rev) {
                out.printf("    <-[%s]- #%d %s%s%n",
                        e.type(), e.src(), monikerOf(symbols, e.src()), occ(e.occurrence(), symbols));
            }
            return 0;
        } catch (Exception e) {
            err.println("jcma: index-dump failed: " + e.getMessage());
            return 1;
        }
    }

    /** Declaration site of {@code s}: {@code file<id>:line:col}, or {@code <external>} for a phantom. */
    private static String site(Symbol s) {
        if (s.isPhantom() || s.range().isNone()) {
            return "<external>";
        }
        return "file" + s.fileId() + ":" + s.range().startLine() + ":" + s.range().startCol();
    }

    private static String monikerOf(SymbolStore symbols, int id) {
        return id >= 0 && id < symbols.size() ? symbols.monikerOf(id) : "<id " + id + ">";
    }

    /** Render an occurrence's use site: {@code  @ file<id>:line:col (role, in #enc)}, empty if NONE. */
    private static String occ(Occurrence o, SymbolStore symbols) {
        if (o.isNone()) {
            return "";
        }
        String enc = o.enclosingSymbolId() < 0 ? "" : ", in #" + o.enclosingSymbolId();
        return "  @ file" + o.fileId() + ":" + o.range().startLine() + ":" + o.range().startCol()
                + " (" + o.role() + enc + ")";
    }

    private static void usage(PrintStream err) {
        err.println("jcma: usage: jcma index-dump --symbols <indexDir>");
        err.println("             jcma index-dump --edges <indexDir> <moniker>");
    }
}
