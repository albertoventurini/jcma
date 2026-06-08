# M1 · Task 12 — Virtual-thread cancellable, time-boxed query serving
> Cancellable, bounded query loop (engine-side; MCP wiring is M2).

## Scope override (decided 2026-06-08, supersedes the PRD wording)
The user **explicitly de-scoped two PRD items for M1**, to ship the simple version first and optimize
later (record, don't relitigate):
- **No cross-query concurrency.** A coding agent queries one-at-a-time; the session stays
  single-writer. No locking, **no race test**, no `QueryResult` partial wrapper.
- **No best-effort partial on timeout.** A timed-out query reports a clean timeout, not a partial
  answer assembled from edges resolved so far. (Partial assembly is a later optimization.)
- **No `StructuredTaskScope`.** It is still a **preview** API in JDK 25 (JEP 505) and this build
  carries no `--enable-preview` (which would have to thread through compile/test/run/native-image).
  Virtual threads are stable, so we use a **single-thread virtual-thread executor** + `Future.get(
  deadline)` + `cancel(true)`: single-thread structurally enforces one-writer-at-a-time, and the
  caller returns promptly even if a worker stalls.

## Prerequisites (read first, fresh session)
- **Done before this:** task-10 (EdgeResolver), task-11 (invalidation) — the work being scheduled.
- **Read:** PRD §5.1 (cancellable/time-boxed serving) + §6 ; M1 overview ; M0-RESULTS
  §"Performance & memory (Spike B)" (latency targets).
- **Port from M0 (reference, don't extend):** `SpikeB` latency harness (p50/p90/p99) for the perf tests.

## Protocol (test-first; full version in the overview)
Write failing tests + fixtures → **STOP for review** → implement → verify.

## Scope — files to create/modify
- `src/main/java/jcma/query/QueryService.java` — wraps & owns an `AnalysisSession`; runs each query
  on a single-thread virtual-thread executor with a **time-box** (`Future.get(deadline)`) +
  **cancellation** (`cancel(true)`). On timeout throws **`QueryTimeoutException`** (never crash,
  never hang). One worker thread → no concurrent store mutation.
- `src/main/java/jcma/query/QueryTimeoutException.java` — checked; carries the deadline.
- `src/main/java/jcma/resolve/EdgeResolver.java` — a cooperative cancel **checkpoint** at the top of
  the `ensureResolved` candidate-file loop (throws unchecked `CancellationException` when the worker
  is interrupted), so cancellation is prompt and never tears a mid-file `applyEdit`.
- `src/main/java/jcma/cli/` — `--deadline <ms>` flag on `refs`/`def`/`supertypes`, and routed through
  the `repl` driver (per-line `--deadline`).

## Tests (red-first)
- `QueryServiceTest`: a long (synthetic) op exceeds its deadline → `QueryTimeoutException` **promptly**
  and the worker observes the cancellation and stops; a generous deadline returns the **full** answer.
- `EdgeResolverCancellationTest`: a pre-interrupted `find_references` throws `CancellationException`
  **before resolving any candidate file** (the checkpoint is wired).
- `QueryLatencyTest` (assumes the corpus): warm `find_references` **p95 < 200 ms** (ported `pct()`).

## Manual CLI check
- `jcma refs <repo> <hot-symbol> --deadline 50ms` → clean timeout within budget; a generous
  `--deadline` returns the full answer (commons-lang corpus).

## Done when
- tests green · native green · timeout throws `QueryTimeoutException` promptly (never hangs) ·
  warm find_references p95 < 200 ms with the edge cache (§Targets) — fold into M1-RESULTS.

## Outcome (2026-06-08)
- ✅ All tests green (`QueryServiceTest`, `EdgeResolverCancellationTest`, `QueryLatencyTest`); full
  suite green at `maxHeapSize = "2g"` (see M1-RESULTS caveat — the corpus ITs overflow the default
  512 MB heap when the local corpus is present).
- ✅ **Warm find_references p95 = 2.61 ms** (p50 1.68 · p99 2.89 · max 6.53; n=200) — folded into
  M1-RESULTS.
- ✅ Cancellation + time-box: `QueryTimeoutException` thrown promptly, worker stops cooperatively.
</content>
