# M2 · Task 10 — In-session new-file discovery (the find_references completeness owner)

> Spotted in real-repo use (`tiko-fraud-facial-recognition`): `refs MatchedFace` found 37 refs;
> after creating a **new** file referencing `MatchedFace`, re-running `refs MatchedFace` did **not**
> find it. Detection already works (the tree-scan source reports the new path); the gap is
> **handling** — a brand-new untracked file is never allocated an id, never Tier-1 indexed, and is
> never a `find_references` candidate. **This task supersedes the M1 task-09 → task-11 deferral**
> ("cross-file discovery of a brand-new referrer is task-11"): task-11a/b/c shipped the node-diff
> cascade but never closed new-file candidate discovery. This task is its completeness owner.

## Prerequisites (read first, fresh session)
- **Read:** PRD §5.1 (freshness pipeline + invalidation cascade). The code anchors are in the Root
  cause below — read those files, not the whole tree.
- **Builds on:** M1 task-09 (`FreshnessGuard` / `reindexOne` / on-access backstop), M1
  task-11a/b/c (node-diff cascade, `AnalysisSession`, tree-scan `FreshnessSource`).
- **Sibling issue:** [task-09-repl-startup-latency.md](task-09-repl-startup-latency.md) is the
  *other* real-repo issue from the same session; independent of this one.

## Protocol (test-first — the standing gate)
Write failing tests + fixtures → **STOP for review (red-pause-green gate)** → implement → verify.
Observability throughout; commit on the current branch.

---

## Root cause (confirmed)
Detection works; **handling** is the gap.
- `TreeScanSource.drainChanged()` **does** report a brand-new file
  (`src/main/java/jcma/workspace/TreeScanSource.java:51` — `prev == null` → reported), and the REPL
  wires `TreeScanSource` (`src/main/java/jcma/cli/Repl.java:57`). So the new file's path reaches
  `Cascade.refresh(changed)`.
- `Cascade.refresh` sees `fid < 0` for the untracked file and calls `guard.reindexOne(file)`
  (`src/main/java/jcma/resolve/Cascade.java:53-56`), which hits the `row == null` branch and
  **no-ops** (`src/main/java/jcma/workspace/FreshnessGuard.java:105-106`, explicitly commented
  "cross-file discovery of a brand-new referrer is task-11"). The file is never allocated an id,
  never Tier-1 indexed, never added to the store.
- Even if it were indexed, `find_references` discovers candidates **only** from the static mmap'd
  `usage-names.seg` (`EdgeResolver.ensureResolved` → `UsageNameIndex.candidateFiles`,
  `src/main/java/jcma/resolve/EdgeResolver.java:220-232`), which is built once at index time and
  excludes session-new files.

This is the documented-but-never-closed deferral: M1 task-09 → task-11 (trigram-pruned candidate
set); task-11a/b/c shipped the node-diff cascade but **not** new-file candidate discovery.

## Fix (two parts)

### Part A — index new files in the freshness path
Handle the *untracked-but-exists* case (today's `reindexOne` no-op). Mirror `Reconciler`'s NEW branch
(`src/main/java/jcma/workspace/Reconciler.java:106-110`) using the existing `FileTable` API
(`allocateId()` / `put` / `save`, all present): allocate an id, Tier-1 index the file
(`Indexer.indexFile`), `store.applyEdit`, add the row, persist. Return a `NodeDiff` whose symbols are
all "added" so the cascade's existing added-names path
(`src/main/java/jcma/resolve/Cascade.java:82-83,89` → `invalidateReferrers` over
`rev(name~UNRESOLVED)`) re-binds prior unconfirmed refs to any name the new file now *defines*. Route
`Cascade.refresh`'s `fid < 0` branch into this instead of the bare `reindexOne` no-op.

### Part B — make new files find_references candidates
Keep the lazy, name-scoped resolve model (don't eagerly value-resolve whole files — that's the
cubic-cost class). Maintain an **in-session usage-name overlay**: when a new file is indexed (Part
A), parse its use-sites with the existing `StructuralParser.usages` (the same source
`UsageNameIndexer` uses) and register the new fileId under each target name in an in-memory
`Map<String, Set<Integer>>`. `EdgeResolver.ensureResolved` and `ensureHierarchyResolved` then union
`usageIndex.candidateFiles(name)` with this overlay, so `warmForReferences(newFid, name)` resolves
the new file for the queried name → confirmed edge into the graph → `store.rev(target.moniker())`
finds it. Drop overlay entries when a file is tombstoned (`reindexOne` MISSING branch).

This is the smallest change consistent with the architecture: detection is already universal
(TreeScanSource), Part A reuses the Reconciler NEW logic + FileTable API, Part B reuses the existing
`StructuralParser.usages` and the lazy per-(file,name) warm path.

## Scope — files to modify
- `src/main/java/jcma/workspace/FreshnessGuard.java` — `reindexOne` `row == null` branch: allocate id
  + Tier-1 index + add row (the NEW case); on tombstone, signal overlay removal.
- `src/main/java/jcma/resolve/Cascade.java` — route the `fid < 0` branch through the new-file index
  path and feed its added names into the cascade.
- `src/main/java/jcma/resolve/EdgeResolver.java` — in-session usage-name overlay; union it into
  `ensureResolved` / `ensureHierarchyResolved` candidate discovery; clear on tombstone.
- *(Possibly)* `src/main/java/jcma/index/UsageNameIndexer.java` — reuse/extract the per-file
  `StructuralParser.usages` walk so the overlay and the persisted index share one code path.
- `src/main/java/jcma/session/AnalysisSession.java` — wire the overlay between guard and resolver if
  it needs to live above both.

## Tests (red-first)
- New file referencing an already-indexed symbol → `find_references` includes it after a refresh.
- New file *defining* a name that had unconfirmed refs → those refs re-bind (confirmed) on next query.
- New file then deleted in-session → `find_references` no longer reports it (overlay + row cleaned).
- Unchanged/nothing-new query still does no extra work (no regression on the common path).

## Manual check (REPL, mirroring the bug report)
- Index the repo; `refs MatchedFace` (baseline N). Create a new file referencing `MatchedFace`;
  `refs MatchedFace` now reports N+1. Delete the file; `refs MatchedFace` returns to N.

## Done when
- tests green · native green · a brand-new in-session referrer is discovered by `find_references`, and
  a brand-new definition re-binds prior unconfirmed refs · common-path queries unaffected.
