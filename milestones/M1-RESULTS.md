# M1 — results & locked decisions

Running record of measured numbers and decisions ratified during M1 (the calibration-first
convention from M0: gates derived from measured actuals + safe-vs-silent failure behaviour, never
round numbers on faith). One section per task as it lands.

---

## Task-02b — native JDK type resolution (host-derived signature index)

### Calibration (Step 0) — how much does project resolution depend on the JDK solver?
Reused the M0 Spike-A `coverage` harness with a new `M0_NO_JDK=1` knob (`SolverSetup.build(..,
withJdkReflection)` drops `ReflectionTypeSolver`, mirroring the native-image gap). Ran with and
without the JDK solver on the two pinned corpora:

| Corpus | coverage **with** JDK | coverage **without** JDK | drop |
|---|---|---|---|
| commons-lang   | 99.48% | 57.63% | **−41.85 pp** |
| jackson-databind | 99.61% | 63.70% | **−35.91 pp** |

(~46.9k / ~115k resolve attempts respectively; excluded-occurrence counts shift slightly between
runs because some `NameExpr`s reclassify when the JDK is unresolvable.)

**Conclusion:** the JDK solver is **first-class**, not a nice-to-have. A JDK-blind native binary
loses ~36–42 points of *all* symbol resolution — not just go-into-JDK, but project symbols whose
resolution routes through JDK intermediates (overload selection, generic types, hierarchy through
JDK supertypes). This closes the calibration loop and confirms the go-decision: build the index.

### Locked design decisions (supersede the task doc's in-process `jmods` plan)
1. **Helper-JVM indexer over in-process `jmods` byte-parsing.** Two findings forced the pivot:
   - the default host JDK on this machine (jimage-only, **no `jmods/`**) can't be indexed by a
     jmods reader at all — and JREs / jlink images lack `jmods` generally;
   - a short-lived **non-native JVM** (`$JAVA_HOME/bin/java`) running *on* the target JDK reads that
     JDK's own classes via the built-in **`jrt:/` filesystem** — works on **every JDK 9+** regardless
     of `jmods`, and pulls **zero JDK-internal API into the native image**.
2. **Reuse the proven `JarTypeSolver` path.** The helper emits a plain **jar** of de-moduled JDK
   classes; the native side feeds it to `JarTypeSolver` — the exact native-safe javassist byte-parse
   path the Task-02 `--enable-url-protocols=jar` fix already proved. No custom `TypeSolver` subclass.
3. **Native-only; JVM/dev keeps `ReflectionTypeSolver`** (retain a known-good fallback). Selector:
   `"runtime".equals(System.getProperty("org.graalvm.nativeimage.imagecode"))` — no GraalVM class
   dependency. This intentionally leaves the JVM-path solver-ordering wart in place (the native JDK
   solver carries only `java.*`/`jdk.*`, so it can't mis-resolve project types — no wart on native).
4. **Fingerprint-cached per JDK version.** Fast hash of `$JAVA_HOME/release` + `lib/modules` size →
   `${XDG_CACHE_HOME:-$HOME/.cache}/jcma/jdk-<fp>.jar`; temp-file + atomic rename guards concurrent
   first-runs. (FNV-1a 64-bit for now; xxHash64 is the eventual project-wide hash — PRD §5.1.)

### Measured (this machine: GraalVM/Temurin-class JDK 25, jimage-only)
- `jrt:/modules` exposed **27,045** classes incl. `java/util/Arrays.class`; de-moduled jar ≈ **62 MB**.
- **Cache miss** (first run, builds the index via the helper JVM): ~**10 s** wall.
- **Cache hit** (normal path, no subprocess): ~**0.2 s** wall.
- Native binary resolves the formerly-unresolved targets:
  `jcma resolve …/Main.java 43:21` → `java.io.PrintStream.println(java.lang.String)`,
  `declared: <external (jar/jdk)>` (`jdkResolveSmoke`, was red — now green).

### Caveats (carried)
- **Host JDK older than the indexer's bytecode:** indexer compiled at `--release 17`; jcma targets
  JDK 25+ projects, so host ≥ 25 in practice.
- **Cache size:** full JDK repacked ≈ tens of MB under `~/.cache/jcma`; body-stripping (signatures
  only) is a later optimization.
- **JDK discovery is `JAVA_HOME`-only** for now; project-toolchain discovery is deferred (a future
  sibling of `Workspace`'s classpath discovery). Any failure degrades to no JDK solver + a
  `JCMA_DEBUG` diagnostic (parity with pre-02b native behaviour, now the exception not the rule).

---

## Task-12 — virtual-thread cancellable, time-boxed query serving

### Locked design decisions (supersede the task doc's PRD-derived plan; user-approved 2026-06-08)
1. **No cross-query concurrency, no partial-on-timeout.** The consumer is a coding agent that queries
   one at a time, so the session stays single-writer; cross-query concurrency and best-effort partial
   results are deferred optimizations. A timed-out query reports a clean timeout (`QueryTimeoutException`),
   not a partial answer.
2. **No `StructuredTaskScope`** — it is still a *preview* API in JDK 25 (JEP 505) and the build carries
   no `--enable-preview` (which would thread through compile/test/run/native-image). Virtual threads are
   stable, so `QueryService` uses a **single-thread virtual-thread executor** + `Future.get(deadline)` +
   `cancel(true)`. Single-thread structurally enforces one-writer-at-a-time; the caller returns promptly
   even if a worker stalls.
3. **Cooperative cancel checkpoint** in `EdgeResolver.ensureResolved` (top of the candidate-file loop):
   `Thread.currentThread().isInterrupted()` → unchecked `CancellationException`. Between files, never
   mid-`applyEdit` (the overlay log is a plain `DataOutputStream`, not an interruptible NIO channel, so
   an interrupt cannot tear it). `--deadline <ms>` on `refs`/`def`/`supertypes` + the REPL.

### Measured (this machine: GraalVM/Temurin-class JDK 25; commons-lang corpus, n=200)
- **§8/§Targets latency gate met with vast headroom — warm `find_references` p95 = 2.61 ms**
  (p50 1.68 · p90 2.49 · p99 2.89 · max 6.53 ms), vs the < 200 ms budget. The Tier-2 edge cache turns
  repeat queries into pure graph walks — confirms the M0 prediction (cold worst 262 ms → warm lookups).
- **Cancellation/time-box**: a deadline-exceeded query throws `QueryTimeoutException` promptly and the
  worker observes the interrupt and stops; a pre-interrupted `find_references` bails before resolving any
  candidate file (`EdgeResolverCancellationTest`). No hang, no crash.

### Caveats (carried)
- **Test-heap floor for the corpus ITs.** The opt-in corpus ITs (`EdgeResolverCommonsIT`,
  `QueryLatencyTest`; both `assumeTrue` a local, git-ignored commons-lang corpus) resolve a 527-file
  project through JavaSymbolSolver, whose retained AST set overflows Gradle's default 512 MB test heap
  into a GC death-spiral. Raised the test JVM to `maxHeapSize = "2g"` (build.gradle.kts). On a normal
  checkout the corpus is absent and these skip, so CI is unaffected.
- **Pre-existing perf hotspot (NOT task-12):** `EdgeResolverCommonsIT` takes ~721 s even at 2 g —
  cold-resolving overloaded symbols across the whole corpus. Flagged for a separate look.
