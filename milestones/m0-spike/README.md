# M0 spike harness (throwaway)

De-risking spike for `jcma` — see [`../M0-de-risking-spike.md`](../M0-de-risking-spike.md).
**Throwaway code:** deleted after `M0-RESULTS.md` is written; M1 starts clean.

This session built the **harness only** (project + corpus + wiring smoke). The gated spikes
(A accuracy, B perf/memory, C native-image, D incremental format) are **not run yet**.

## Pinned versions

| Thing | Pin |
|---|---|
| JDK | Temurin 25 (`25+36-LTS`), via SDKMAN `current` |
| Engine dep | `com.github.javaparser:javaparser-symbol-solver-core:3.28.2` (documents Java 1–25) |
| Accuracy/labeling repo | apache/commons-lang @ `rel/commons-lang-3.20.0` — SHA `598dfc163b8b410fb3bb8794521206ec8dcec82a` |
| Scale/perf repo | FasterXML/jackson-databind @ `jackson-databind-2.20.2` — SHA `34097b77d41b7ff835fdbe9bf274b96a0c640df9` |
| Native-image distro (Spike C, later) | GraalVM CE for JDK 25 (SDKMAN; not installed yet) |

Source roots: both repos use `src/main/java` (commons-lang 259 files, jackson-databind 481).

## Layout

```
m0-spike/
├─ pom.xml                       # javaparser-symbol-solver-core:3.28.2 + shade (fat-jar for Spike C)
├─ src/main/java/m0/HarnessSmoke.java
└─ corpus/
    ├─ commons-lang/      + cp.txt   (21 jars; incl. test deps)
    └─ jackson-databind/  + cp.txt   (38 jars; jackson-core/annotations etc.)
```

## Reproduce

```sh
# 1. corpus checkout (already done)
cd corpus
git clone --depth 1 --branch rel/commons-lang-3.20.0 https://github.com/apache/commons-lang
git clone --depth 1 --branch jackson-databind-2.20.2  https://github.com/FasterXML/jackson-databind

# 2. dependency classpath (manual classpath SymbolSolver consumes; PRD §4 / M0 Spike A.1)
( cd commons-lang     && mvn -q dependency:build-classpath -Dmdep.outputFile=cp.txt )
( cd jackson-databind && mvn -q dependency:build-classpath -Dmdep.outputFile=cp.txt )

# 3. build + run the wiring smoke (from m0-spike/)
mvn -q package -DskipTests
java -jar target/m0-spike.jar corpus/commons-lang/src/main/java     corpus/commons-lang/cp.txt
java -jar target/m0-spike.jar corpus/jackson-databind/src/main/java corpus/jackson-databind/cp.txt
```

## Smoke result (this session)

Wiring proven end-to-end: parse @ `LanguageLevel.JAVA_25` + ReflectionTypeSolver (JDK) +
JavaParserTypeSolver (project source) + JarTypeSolver (deps) → method calls actually resolve.

| Repo | files (budget 60) | parse-fail | method calls | resolved |
|---|---|---|---|---|
| commons-lang | 60 | 0 | 2741 | **94.7%** |
| jackson-databind | 60 | 0 | 2161 | **100%** |

Smoke only (a 60-file slice, not the gated measurement). Notes for Spike A:
- **JDK-25 syntax parses clean** — zero parse failures; the recorded "confirm JDK-25 parsing"
  risk is retired for these repos.
- jackson-databind's 100% includes **cross-jar** resolution into jackson-core → JarTypeSolver
  wiring is correct.
- The ~5% unresolved on commons-lang is the *kind* of signal Spike A must bucket into the
  failure-cause histogram (generics/overloads/lambdas/var/…), over the **whole** repo, not a
  slice — not a pass/fail here.

## Next (after review) — gated spikes, see M0 doc
A → B (reuse A harness) → C (needs GraalVM install) → D (independent). Output:
`../M0-RESULTS.md` decision memo (gate table + failure histogram + reachability config +
GO/FALLBACK).
