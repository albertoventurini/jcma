package m0;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ParserConfiguration.LanguageLevel;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.expr.MethodCallExpr;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * M0 Spike B (throwaway) — performance & memory at scale. Reuses Spike A's harness
 * (SolverSetup + SpikeA.{javaFiles,parse,occurrences,attempt}). Modes:
 *   throughput <src> <cp> <label> <out>   -> G5 parse-only LOC/sec (parallel vs sequential)
 *   memory     <src> <cp> <label> <out>   -> G6 RSS/heap curve under sustained resolution
 *   latency    <src> <cp> <label> <out>   -> G7 per-file resolve p50.. + simulated find_references
 */
public final class SpikeB {

    public static void main(String[] args) throws Exception {
        if (args.length < 5) {
            System.err.println("usage: SpikeB <throughput|memory|latency> <src> <cp> <label> <out>");
            System.exit(2);
        }
        String mode = args[0];
        Path src = Path.of(args[1]);
        Path cp = Path.of(args[2]);
        String label = args[3];
        Path out = Path.of(args[4]);
        Files.createDirectories(out);

        switch (mode) {
            case "throughput" -> throughput(src, label, out);
            case "memory"     -> memory(src, cp, label, out);
            case "latency"    -> latency(src, cp, label, out);
            default -> { System.err.println("unknown mode: " + mode); System.exit(2); }
        }
    }

    // ---------------------------------------------------------------- G5 throughput

    private static void throughput(Path src, String label, Path out) throws Exception {
        List<Path> files = SpikeA.javaFiles(src);
        long loc = 0;
        for (Path f : files) loc += countLines(f);
        int cores = Runtime.getRuntime().availableProcessors();

        // parse-only: no symbol solver (parsing = the Tier-1 cost).
        // warm-up to absorb JIT / classloading.
        for (int i = 0; i < Math.min(20, files.size()); i++) parseOnly(files.get(i));

        // sequential baseline
        long s0 = System.nanoTime();
        for (Path f : files) parseOnly(f);
        double seqS = (System.nanoTime() - s0) / 1e9;

        // parallel via virtual threads
        long p0 = System.nanoTime();
        AtomicLong ok = new AtomicLong();
        try (ExecutorService vt = Executors.newVirtualThreadPerTaskExecutor()) {
            List<java.util.concurrent.Future<?>> fs = new ArrayList<>();
            for (Path f : files) fs.add(vt.submit(() -> { if (parseOnly(f) != null) ok.incrementAndGet(); }));
            for (var fut : fs) fut.get();
        }
        double parS = (System.nanoTime() - p0) / 1e9;

        StringBuilder sb = new StringBuilder();
        sb.append("# Spike B — throughput (G5): ").append(label).append("\n\n");
        sb.append(String.format("Files: %d · LOC: %,d · cores: %d (parsed ok: %d)%n%n", files.size(), loc, cores, ok.get()));
        sb.append("Parse-only (no symbol solver). Parsing is CPU-bound → speedup is bounded by cores.\n\n");
        sb.append("| run | wall (s) | LOC/sec | files/sec |\n|---|---|---|---|\n");
        sb.append(String.format("| sequential | %.2f | %,.0f | %,.0f |%n", seqS, loc / seqS, files.size() / seqS));
        sb.append(String.format("| parallel (vthreads) | %.2f | %,.0f | %,.0f |%n", parS, loc / parS, files.size() / parS));
        sb.append(String.format("%nSpeedup: %.1f×%n", seqS / parS));
        Path p = out.resolve("throughput-" + label + ".md");
        Files.writeString(p, sb.toString());
        System.out.printf("[throughput] %s: %,d LOC, seq %.2fs / par %.2fs (%.1f×), %,.0f LOC/s parallel -> %s%n",
                label, loc, seqS, parS, seqS / parS, loc / parS, p);
    }

    // ---------------------------------------------------------------- G6 memory

    private static void memory(Path src, Path cp, String label, Path out) throws Exception {
        List<Path> files = SpikeA.javaFiles(src);
        StringBuilder curve = new StringBuilder();

        // (a) idle/post-init: build the resolving wiring (loads dependency jars) + warm-up.
        SolverSetup.Wiring w = SolverSetup.build(src, cp);
        for (int i = 0; i < Math.min(20, files.size()); i++) {
            CompilationUnit cu = SpikeA.parse(w, files.get(i));
            if (cu != null) for (var e : SpikeA.occurrences(cu)) SpikeA.attempt(e.getKey(), e.getValue());
        }
        long rssIdle = gcThenRss();
        long heapIdle = usedHeapMb();

        // (b) post parse-only scan: parse all, keep only counts, discard, GC.
        long occCount = 0;
        for (Path f : files) {
            CompilationUnit cu = parseOnly(f);
            if (cu != null) occCount += cu.findAll(com.github.javaparser.ast.Node.class).size();
        }
        long rssParse = gcThenRss();
        long heapParse = usedHeapMb();

        // (c) sustained resolution sweep, sampling every 25 files. The heap column is taken
        // AFTER a light GC so it shows the live retained set (cache growth), not allocation churn.
        curve.append("| files | VmRSS MB | usedHeapAfterGC MB |\n|---|---|---|\n");
        long peakRss = rssParse;
        int idx = 0;
        for (Path f : files) {
            CompilationUnit cu = SpikeA.parse(w, f);
            if (cu != null) for (var e : SpikeA.occurrences(cu)) SpikeA.attempt(e.getKey(), e.getValue());
            if (++idx % 25 == 0) {
                long heap = gcThenUsedHeapMb();
                long rss = rssMb();
                peakRss = Math.max(peakRss, rss);
                curve.append(String.format("| %d | %d | %d |%n", idx, rss, heap));
            }
        }
        long rssEnd = rssMb();
        long heapEnd = usedHeapMb();
        peakRss = Math.max(peakRss, rssEnd);

        // crude plateau check: compare post-GC used heap at the mid vs end of the sweep.
        long heapResolveEnd = gcThenUsedHeapMb();

        StringBuilder sb = new StringBuilder();
        sb.append("# Spike B — memory (G6): ").append(label).append("\n\n");
        sb.append(String.format("Files: %d · total AST nodes (parse-only): ~%,d%n%n", files.size(), occCount));
        sb.append("RSS = process VmRSS (committed; inflated by -Xmx). usedHeapAfterGC = live set (the true\n");
        sb.append("signal for SymbolSolver cache growth). JVM+jars baseline, no compact index yet (RSS is\n");
        sb.append("structurally above the eventual product).\n\n");
        sb.append("| phase | VmRSS MB | usedHeapAfterGC MB |\n|---|---|---|\n");
        sb.append(String.format("| (a) idle/post-init+warm | %d | %d |%n", rssIdle, heapIdle));
        sb.append(String.format("| (b) post parse-only scan | %d | %d |%n", rssParse, heapParse));
        sb.append(String.format("| (c) post resolution sweep | %d | %d |%n", rssEnd, heapResolveEnd));
        sb.append(String.format("%n**Peak VmRSS during sweep: %d MB.**%n%n", peakRss));
        sb.append("## Resolution-sweep curve (every 25 files)\n");
        sb.append(curve);
        Path p = out.resolve("memory-" + label + ".md");
        Files.writeString(p, sb.toString());
        System.out.printf("[memory] %s: idle %dMB, postParse %dMB, postResolve(gc) %dMB, peakRSS %dMB -> %s%n",
                label, rssIdle, rssParse, heapResolveEnd, peakRss, p);
    }

    // ---------------------------------------------------------------- G7 latency

    private static void latency(Path src, Path cp, String label, Path out) throws Exception {
        List<Path> files = SpikeA.javaFiles(src);
        SolverSetup.Wiring w = SolverSetup.build(src, cp);

        // pre-parse + cache source text (the product holds Tier-1 parsed; find_references is resolve-only).
        Map<Path, CompilationUnit> cus = new LinkedHashMap<>();
        Map<Path, String> text = new HashMap<>();
        for (Path f : files) {
            CompilationUnit cu = SpikeA.parse(w, f);
            if (cu != null) { cus.put(f, cu); text.put(f, readText(f)); }
        }
        // warm-up
        int wu = 0;
        for (CompilationUnit cu : cus.values()) { if (wu++ >= 10) break;
            for (var e : SpikeA.occurrences(cu)) SpikeA.attempt(e.getKey(), e.getValue()); }

        // (1) per-file COLD resolve latency (first touch) — also collects symbols for (2).
        List<Double> perFileMs = new ArrayList<>();
        Map<String, Long> sigCount = new HashMap<>();
        Map<String, String> sigToName = new HashMap<>();
        for (CompilationUnit cu : cus.values()) {
            long t0 = System.nanoTime();
            for (var e : SpikeA.occurrences(cu)) SpikeA.attempt(e.getKey(), e.getValue());
            perFileMs.add((System.nanoTime() - t0) / 1e6);
            for (MethodCallExpr mce : cu.findAll(MethodCallExpr.class)) {
                try {
                    String sig = mce.resolve().getQualifiedSignature();
                    if (sig.startsWith("org.apache") || sig.startsWith("com.fasterxml")) {
                        sigCount.merge(sig, 1L, Long::sum);
                        sigToName.putIfAbsent(sig, mce.getNameAsString());
                    }
                } catch (Throwable ignore) {}
            }
        }

        // (2) simulated find_references (WARM caches now) on top ~10 project symbols.
        List<String> targets = sigCount.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .map(Map.Entry::getKey).limit(10).toList();

        StringBuilder fr = new StringBuilder();
        fr.append("| symbol | candidate files | found refs | latency ms |\n|---|---|---|---|\n");
        for (String target : targets) {
            String n = sigToName.get(target);
            long t0 = System.nanoTime();
            int candidates = 0, found = 0;
            for (var en : cus.entrySet()) {
                if (!text.get(en.getKey()).contains(n)) continue; // trigram/grep prune
                candidates++;
                for (MethodCallExpr mce : en.getValue().findAll(MethodCallExpr.class)) {
                    if (!mce.getNameAsString().equals(n)) continue;
                    try { if (target.equals(mce.resolve().getQualifiedSignature())) found++; }
                    catch (Throwable ignore) {}
                }
            }
            double ms = (System.nanoTime() - t0) / 1e6;
            fr.append(String.format("| `%s` | %d | %d | %.1f |%n", shortSig(target), candidates, found, ms));
        }

        Collections.sort(perFileMs);
        StringBuilder sb = new StringBuilder();
        sb.append("# Spike B — latency (G7): ").append(label).append("\n\n");
        sb.append(String.format("Files: %d. §8 target: find_definition/refs p95 < 200ms (native, persisted).%n%n", cus.size()));
        sb.append("## Per-file resolve latency (COLD first-touch — the lazy-resolve unit cost)\n");
        sb.append(String.format("p50 %.1f ms · p90 %.1f ms · p99 %.1f ms · max %.1f ms%n%n",
                pct(perFileMs, 50), pct(perFileMs, 90), pct(perFileMs, 99), perFileMs.get(perFileMs.size() - 1)));
        sb.append("## Simulated find_references (WARM caches = steady-state query)\n");
        sb.append("Trigram/grep-pruned candidate set (files whose text contains the simple name), resolve-only.\n\n");
        sb.append(fr);
        Path p = out.resolve("latency-" + label + ".md");
        Files.writeString(p, sb.toString());
        System.out.printf("[latency] %s: per-file resolve p50 %.1f / p99 %.1f ms; %d find_references symbols -> %s%n",
                label, pct(perFileMs, 50), pct(perFileMs, 99), targets.size(), p);
    }

    // ---------------------------------------------------------------- helpers

    private static final ParserConfiguration PARSE_ONLY =
            new ParserConfiguration().setLanguageLevel(LanguageLevel.JAVA_25);

    private static CompilationUnit parseOnly(Path f) {
        try {
            var r = new JavaParser(PARSE_ONLY).parse(f);
            return r.isSuccessful() ? r.getResult().orElse(null) : null;
        } catch (IOException e) {
            return null;
        }
    }

    private static long countLines(Path f) {
        try { return Files.readAllLines(f).size(); } catch (IOException e) { return 0; }
    }

    private static String readText(Path f) {
        try { return Files.readString(f); } catch (IOException e) { return ""; }
    }

    private static long rssMb() {
        try {
            for (String line : Files.readAllLines(Path.of("/proc/self/status"))) {
                if (line.startsWith("VmRSS:")) {
                    String[] parts = line.trim().split("\\s+"); // VmRSS: <num> kB
                    return Long.parseLong(parts[1]) / 1024;
                }
            }
        } catch (IOException ignore) {}
        return -1;
    }

    private static long usedHeapMb() {
        Runtime rt = Runtime.getRuntime();
        return (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
    }

    private static long gcThenRss() { lightGc(); return rssMb(); }
    private static long gcThenUsedHeapMb() { lightGc(); return usedHeapMb(); }

    private static void lightGc() {
        for (int i = 0; i < 3; i++) { System.gc(); try { Thread.sleep(120); } catch (InterruptedException ignore) {} }
    }

    private static double pct(List<Double> sorted, int p) {
        if (sorted.isEmpty()) return 0;
        int i = (int) Math.ceil(p / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(i, sorted.size() - 1)));
    }

    private static String shortSig(String qualifiedSignature) {
        int paren = qualifiedSignature.indexOf('(');
        String head = paren < 0 ? qualifiedSignature : qualifiedSignature.substring(0, paren);
        int dot = head.lastIndexOf('.', head.lastIndexOf('.') - 1);
        String tail = dot < 0 ? head : head.substring(dot + 1);
        return paren < 0 ? tail : tail + qualifiedSignature.substring(paren);
    }
}
