package jcma.cli;

import jcma.engine.Position;
import jcma.index.MonikerEdge;
import jcma.index.Symbol;
import jcma.obs.Metrics;
import jcma.resolve.Definition;
import jcma.resolve.Ref;
import jcma.resolve.ReferenceGroup;
import jcma.resolve.References;
import jcma.resolve.UnconfirmedRef;
import jcma.session.AnalysisSession;
import jcma.workspace.FreshnessSource;
import jcma.workspace.TreeScanSource;
import jcma.workspace.Workspace;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * {@code jcma repl <repo>} (M1 task-11c) — a tiny long-running query loop over a single
 * {@link AnalysisSession}. Unlike the one-shot subcommands (a fresh process, cold cache, per query),
 * the REPL keeps the session — and its Tier-2 edge cache — alive across queries, and drives a
 * {@link TreeScanSource} so an out-of-band edit is detected, re-indexed, and <b>cascaded</b> before
 * the next query serves an answer. This is the in-process model the MCP server (M2) will use; here it
 * makes the warm cache-hit + live node-diff cascade observable by hand (the task's manual check).
 */
final class Repl {

    private Repl() {}

    static int run(String[] args, PrintStream out, PrintStream err) {
        if (args.length != 2) {
            err.println("jcma: usage: jcma repl <repo>");
            return 2;
        }
        Path repo = Path.of(args[1]);
        Path indexDir = repo.resolve(".jcma");
        if (!Files.isDirectory(indexDir)) {
            err.println("jcma: no index at " + indexDir + " — run `jcma index " + repo + "` first");
            return 1;
        }
        Workspace workspace = Workspace.discover(repo);
        FreshnessSource source = new TreeScanSource(workspace.sourceRoots());
        try (AnalysisSession session = AnalysisSession.open(indexDir, workspace, source, Metrics.noop());
                BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            out.println("jcma repl — commands: refs <symbol> | def <symbol> | def <file> <line:col> "
                    + "| supertypes <symbol> | quit");
            prompt(out);
            String line;
            while ((line = in.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.equals("quit") || trimmed.equals("exit")) {
                    break;
                }
                if (!trimmed.isEmpty()) {
                    try {
                        dispatch(session, trimmed.split("\\s+"), out, err);
                    } catch (Exception e) {
                        err.println("jcma: " + e.getMessage());
                    }
                }
                prompt(out);
            }
            return 0;
        } catch (Exception e) {
            err.println("jcma: repl failed: " + e.getMessage());
            return 1;
        }
    }

    private static void dispatch(AnalysisSession session, String[] cmd, PrintStream out, PrintStream err)
            throws Exception {
        switch (cmd[0]) {
            case "refs" -> {
                if (cmd.length != 2) {
                    err.println("usage: refs <symbol>");
                    return;
                }
                forEachDeclaration(session, cmd[1], out, err, target -> printRefs(out, session.findReferences(target)));
            }
            case "supertypes" -> {
                if (cmd.length != 2) {
                    err.println("usage: supertypes <symbol>");
                    return;
                }
                forEachDeclaration(session, cmd[1], out, err, target -> printHierarchy(out, session, target));
            }
            case "def" -> {
                if (cmd.length == 2) {
                    forEachDeclaration(session, cmd[1], out, err, target -> printDef(out, session.findDefinition(target)));
                } else if (cmd.length == 3) {
                    Position pos = parsePosition(cmd[2]);
                    if (pos == null) {
                        err.println("bad position '" + cmd[2] + "' — expected <line:col> (1-based)");
                        return;
                    }
                    Optional<Definition> def = session.findDefinitionAt(Path.of(cmd[1]), pos);
                    if (def.isEmpty()) {
                        err.println("unresolved at " + cmd[1] + ":" + cmd[2]);
                    } else {
                        printDef(out, def.get());
                    }
                } else {
                    err.println("usage: def <symbol>  |  def <file> <line:col>");
                }
            }
            default -> err.println("unknown command '" + cmd[0] + "' — try: refs | def | supertypes | quit");
        }
    }

    /** Resolve {@code symbol} to its declaration(s) and apply {@code action} to each; report if none. */
    private static void forEachDeclaration(AnalysisSession session, String symbol, PrintStream out,
            PrintStream err, ThrowingConsumer<Symbol> action) throws Exception {
        List<Symbol> targets = session.declarations(symbol);
        if (targets.isEmpty()) {
            err.println("no declaration named '" + symbol + "' in the index");
            return;
        }
        for (Symbol target : targets) {
            action.accept(target);
        }
    }

    private static void printRefs(PrintStream out, References refs) {
        out.printf("  %d reference(s) in %d enclosing symbol(s)%n", refs.totalRefs(), refs.groups().size());
        for (ReferenceGroup g : refs.groups()) {
            out.printf("  %s  (%d)%n", g.enclosingSignature(), g.count());
            for (Ref r : g.refs()) {
                out.printf("    %s:%d  %s%n", r.file().getFileName(), r.range().startLine(), r.snippet());
            }
        }
        if (refs.hasUnconfirmedTail()) {
            out.printf("  unconfirmed (%d):%n", refs.unconfirmed().size());
            for (UnconfirmedRef u : refs.unconfirmed()) {
                out.printf("    %s:%d  %s  [%s]%n", u.file().getFileName(), u.range().startLine(), u.snippet(), u.cause());
            }
        }
    }

    private static void printHierarchy(PrintStream out, AnalysisSession session, Symbol target)
            throws java.io.IOException {
        out.println("  supertypes (out):");
        for (MonikerEdge e : session.supertypes(target)) {
            out.printf("    %-11s %s%n", e.type().name().toLowerCase(), session.signatureOf(e.dst()));
        }
        out.println("  subtypes / overriders (in):");
        for (MonikerEdge e : session.subtypes(target)) {
            out.printf("    %-11s %s%n", e.type().name().toLowerCase(), session.signatureOf(e.src()));
        }
    }

    private static void printDef(PrintStream out, Definition def) {
        out.println("  signature: " + def.signature());
        out.println("  declared:  " + (def.file() == null ? "<external (jar/jdk)>" : def.file() + ":" + def.line()));
        if (!def.snippet().isEmpty()) {
            out.println("  snippet:   " + def.snippet());
        }
    }

    private static Position parsePosition(String s) {
        int colon = s.indexOf(':');
        if (colon <= 0 || colon == s.length() - 1) {
            return null;
        }
        try {
            int line = Integer.parseInt(s.substring(0, colon).trim());
            int col = Integer.parseInt(s.substring(colon + 1).trim());
            return (line >= 1 && col >= 1) ? new Position(line, col) : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static void prompt(PrintStream out) {
        out.print("jcma> ");
        out.flush();
    }

    /** A query action that may throw (the session's query methods declare {@code IOException}). */
    @FunctionalInterface
    private interface ThrowingConsumer<T> {
        void accept(T t) throws Exception;
    }
}
