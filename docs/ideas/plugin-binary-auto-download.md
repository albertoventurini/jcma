# Idea: auto-download the jcma binary from the plugin

**Status:** deferred follow-up to the Claude Code plugin packaging (captured 2026-06-14). The
plugin (`.claude-plugin/` + portable `.mcp.json`) ships the MCP-server *wiring* only; the user
supplies the binary on `PATH` or via `JCMA_BINARY` (LSP-server model). This note records the path
to making install one step instead of two.

## Why it's deferred

Today CI (`.github/workflows/build.yml`) only produces **expiring, auth-gated `upload-artifact`
outputs** — not stable, public download URLs. There's nothing a plugin could `curl` without a
GitHub token and an unexpired run.

## Prerequisites to build it

1. Convert `build.yml` from `upload-artifact` to a **tagged GitHub Releases** pipeline: build
   per-OS/arch native images, attach them as release assets, publish a `SHA256SUMS`.
2. Then either:
   - a small **launcher shim** in the plugin that resolves/downloads the right asset on first run
     (verifying against `SHA256SUMS`) and caches it, or
   - a standalone **`curl | sh` installer** the README points at, keeping the plugin binary-free.

Until then: download separately and put `jcma` on `PATH` (or set `JCMA_BINARY`).
