# Vendored test fixture — Apache Commons Lang (minimal slice)

These two files are copied **verbatim** from Apache Commons Lang and are used only as test data
for `jcma.engine.JavaParserEngineTest#corpusSite1ResolvesToToStringStyle`, which reproduces M0
go-to-def worksheet **site #1**.

- **Source:** https://github.com/apache/commons-lang
- **Pinned version:** `commons-lang 3.20.0` (tag `rel/commons-lang-3.20.0`, commit `598dfc1`)
- **License:** Apache License 2.0 — the original ASF license header is retained in each file.

```
src/org/apache/commons/lang3/AnnotationUtils.java          (call site:  :55 setDefaultFullDetail)
src/org/apache/commons/lang3/builder/ToStringStyle.java    (decl site: :2089)
```

## Why a 2-file slice (not the full corpus)

The site-#1 resolution is **source→source** (`JavaParserTypeSolver` + JDK reflection), so it needs
no dependency classpath — the closure is exactly these two files. Vendoring them keeps the
integration test **hermetic, offline, fast, and deterministic** (the `:2089` assertion is frozen at
the pinned version) and keeps it in the default `./gradlew test` run.

**Do not edit or reformat these files** — the worksheet line numbers (`:55`, `:2089`) are asserted
verbatim. To bump the pinned version, re-copy from the tag and update the asserted lines + this
notice. The `JarTypeSolver`-over-real-jars surface is covered separately by the native cross-jar
smoke (a Gradle-built fixture jar), not here.
