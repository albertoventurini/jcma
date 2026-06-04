# jcma — Java Code-Map for Agents

An agent-native Java (JDK 25+) code-intelligence engine. Its sole consumer is an **AI coding
agent** (Claude Code first), exposed over **MCP only**. Its job is fast, low-memory, semantic
*navigation* of a codebase — not editor features for humans.

## What it is, in one breath
Built on **JavaParser + JavaSymbolSolver**, compiled to a **GraalVM native-image** (tens-of-MB
memory, instant start), serving semantic navigation (symbol search, definitions, references,
hover, type/call hierarchy) as **MCP tools** with context-rich, token-bounded answers.

## Status
**Greenfield — no code yet.** The project is in design + de-risking.
- **M0 (de-risking spike): not started.** It gates everything — it must prove the
  JavaParser→native bet (accuracy, memory/latency, native-image, incremental format) or trigger
  the documented javac-hybrid fallback.
- **M1–M3: deferred** until M0 fires (M1 is partly contingent on M0's results, not just its
  GO/FALLBACK verdict).

## Where things live / reading order
1. **[`PRD.md`](PRD.md)** — the stable *what & why*: context, principles, settled decisions
   (engine, MCP-only, native-image, the §5.1 index design), goals/non-goals, milestones,
   success metrics, open questions. **Read this first.**
2. **[`milestones/`](milestones/)** — the executable *how*, one doc per milestone.
   - [`M0-de-risking-spike.md`](milestones/M0-de-risking-spike.md) — the current work.
3. **[`CLAUDE.md`](CLAUDE.md)** — operating guidance for agents working in this repo.

## Decision snapshot
| | |
|---|---|
| Consumer | AI agents (Claude Code first) — **not** humans in an editor |
| Protocol | **MCP only** (stdio). No LSP surface. |
| Engine | JavaParser + JavaSymbolSolver *(pending M0; javac-hybrid is the fallback)* |
| Distribution | GraalVM native-image, single binary |
| Index | custom memory-mapped store (graph model, lazy-resolve-and-cache) — PRD §5.1 |
| Diagnostics | out of core scope (agents run the build for that) |
