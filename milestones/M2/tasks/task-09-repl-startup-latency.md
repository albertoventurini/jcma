# M2 · Task 09 — REPL startup latency: persist the classpath at index time

> Spotted in real-repo use (`tiko-fraud-facial-recognition`): the **first** `jcma repl` after a
> fresh index/re-index takes ~15 s; subsequent opens take ~1–2 s. The REPL pays two cold costs that
> `jcma index` never warms — a synchronous Gradle/Maven subprocess and an eager read of every
> dependency jar. The fix moves the build-tool subprocess off the REPL hot path by **resolving and
> persisting the classpath during `jcma index`** (tying its freshness to the index lifecycle), and
> surfaces the residual jar-read cost so the wait is never a silent hang.

## Prerequisites (read first, fresh session)
- **Read:** PRD §4 (engine/fallback — why the resolving engine reads jars) ; PRD §5.1 (index
  lifecycle / freshness). The relevant code anchors are in the Root cause below — read those files,
  not the whole tree.
- **Sibling issue:** [task-10-in-session-new-file-discovery.md](task-10-in-session-new-file-discovery.md)
  is the *other* real-repo issue from the same session; independent of this one.

## Protocol (test-first — the standing gate)
Write failing tests + fixtures → **STOP for review (red-pause-green gate)** → implement → verify.
Observability throughout; commit on the current branch.

---

## Root cause (confirmed)
The REPL pays two cold costs that `jcma index` never does:
- `Repl.run` (`src/main/java/jcma/cli/Repl.java:56`) calls `Workspace.discover(repo)` — the **only**
  path that runs `resolveClasspath()` → a synchronous **Gradle/Maven subprocess**
  (`src/main/java/jcma/workspace/Workspace.java:271,316`, `resolveClasspath`/`gradleClasspath`). A
  cold Gradle daemon is many seconds.
- `AnalysisSession.open` builds a `JavaParserEngine` (`src/main/java/jcma/session/AnalysisSession.java:83`)
  whose `JarTypeSolver`s **eagerly read every dependency jar**
  (`src/main/java/jcma/engine/JavaParserEngine.java:72-73`). Cold disk.
- `jcma index` instead uses `Workspace.discoverSourceSets` + `projectRoot`
  (`src/main/java/jcma/cli/Index.java:36,41`) — **no classpath, no resolving engine** — so it never
  warms either.

Net: first REPL open = cold Gradle daemon + cold jar reads (~15 s); second open = warm daemon + OS
page cache (~1–2 s). Re-indexing doesn't refresh any classpath cache, so the cost recurs whenever
the cache is absent.

## Fix (direct — persist/cache the classpath at index time)
1. **Make `jcma index` resolve and persist the classpath.** During `Index.run`, resolve the
   classpath once and write it to an index-dir cache (`<indexDir>/classpath.txt`), tying classpath
   freshness to the index lifecycle (a re-index refreshes it). Reuse the existing `Workspace`
   discovery logic; factor a small `resolveClasspath(projectRoot)` entry point if needed. Cache in
   the **index dir** rather than writing `cp.txt` into the repo root (keeps the working tree clean);
   the existing `cp.txt` precedence is still honored when the user supplies one.
2. **REPL reads the cache instead of re-discovering.** `Repl` / `AnalysisSession.open` build the
   `Workspace` from the cached classpath (read `<indexDir>/classpath.txt`) when present, falling
   back to live discovery only if the cache is missing. This removes the subprocess from the hot
   path entirely.
3. **Surface remaining latency (observability/UX).** The first jar-read cost is inherent to building
   a resolving engine. Print a one-line REPL status (`discovering classpath…` / `loading N
   dependency jars…`) so any residual wait is not a silent hang. Add lightweight startup phase
   timers (classpath / store open / engine build / tree scan) behind the existing `Metrics`/`Timer`
   so the win is measurable and regressions are visible.

## Scope — files to modify
- `src/main/java/jcma/cli/Index.java` — resolve + persist the classpath to the index dir.
- `src/main/java/jcma/workspace/Workspace.java` — expose/cache-aware classpath resolution (read the
  index-dir cache; keep `cp.txt` precedence).
- `src/main/java/jcma/cli/Repl.java` + `src/main/java/jcma/session/AnalysisSession.java` — build the
  `Workspace`/engine from the cached classpath; add the startup status line + phase timers.
- `src/main/java/jcma/workspace/IndexLayout.java` — name/locate the classpath cache file.

## Tests (red-first)
- `jcma index` writes the classpath cache; a subsequent session reads it **without invoking the
  build-tool subprocess** (assert no subprocess spawned / cache file present and consumed).
- Cache-miss falls back to live discovery (existing behavior preserved).
- Re-index refreshes the cache (a dependency change is reflected).

## Manual check
- Re-index the repo, then time two consecutive `jcma repl` opens: the first should no longer pay the
  Gradle-daemon cost (it moved into `jcma index`), and the startup status line is shown.

## Done when
- tests green · native green · first-open no longer runs the build-tool subprocess · the cold cost is
  moved into the explicit `index` step · startup phases are timed.
