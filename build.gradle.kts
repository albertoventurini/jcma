// jcma build — M1 core engine + index.
//
// Locked M1 decisions encoded here:
//  • Java 25 (records, virtual threads, FFM, structured concurrency) — PRD §9.
//  • GraalVM native-image from task 1 onward: every task keeps `nativeCompile` green and runs a
//    native smoke of the `jcma` CLI. M0 Spike C proved the build *order* is load-bearing
//    (agent-trace → bundle metadata → native build); the reachability seed lives as a committed
//    resource under src/main/resources/META-INF/native-image so nativeCompile always sees it.
//  • Engine = JavaParser + JavaSymbolSolver (M0 GO verdict).

plugins {
    application
    id("org.graalvm.buildtools.native") version "1.1.1"
}

group = "jcma"
version = "0.1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.github.javaparser:javaparser-symbol-solver-core:3.28.2")

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
        // GraalVM provides native-image; use it for compile+test+native so the toolchain is one
        // thing. (SDKMAN-installed 25.0.2-graalce is auto-detected by Gradle.)
        vendor = JvmVendorSpec.GRAAL_VM
    }
}

application {
    mainClass = "jcma.cli.Main"
}

tasks.test {
    useJUnitPlatform()
    // The cross-jar engine test (and the -Pagent trace it feeds) resolves against a real fixture
    // jar; build it first. See the crossJarFixtureJar task below.
    dependsOn("crossJarFixtureJar")
}

graalvmNative {
    // The native plugin does not inherit `java.toolchain`; without this it resolves native-image
    // from JAVA_HOME (Temurin here) and fails "JDK isn't a GraalVM distribution". Point its
    // launcher at the detected GraalVM (SDKMAN 25.0.2-graalce) explicitly. Don't remove.
    binaries.all {
        javaLauncher = javaToolchains.launcherFor {
            languageVersion = JavaLanguageVersion.of(25)
            vendor = JvmVendorSpec.GRAAL_VM
        }
    }
    binaries {
        named("main") {
            imageName = "jcma"
            mainClass = "jcma.cli.Main"
            // M0 Spike C findings, carried verbatim:
            //  --no-fallback                 : a real native image, never a JVM-fallback launcher.
            //  --enable-native-access        : FFM mmap store (§5.1) uses Arena/MemorySegment.
            //  +UnlockExperimentalVMOptions
            //  +SharedArenaSupport           : Arena.ofShared() across query threads (else
            //                                  UnsupportedFeatureError at runtime).
            buildArgs.add("--no-fallback")
            buildArgs.add("--enable-native-access=ALL-UNNAMED")
            buildArgs.add("-H:+UnlockExperimentalVMOptions")
            buildArgs.add("-H:+SharedArenaSupport")
            //  --enable-url-protocols=jar    : SymbolSolver's JarTypeSolver resolves dependency
            //                                  classes through javassist, which reads them via
            //                                  `jar:…!/…` URLs (JarClassPath.openClassfile). The
            //                                  `jar:` protocol is off by default in native-image, so
            //                                  without this cross-jar resolution fails at runtime
            //                                  with NotFoundException. This — not reflection metadata
            //                                  — is what the M0 "JarTypeSolver under native" question
            //                                  needed (see milestones/M0-RESULTS.md, Task-02 finding).
            buildArgs.add("--enable-url-protocols=jar")
        }
    }
    // `-Pagent` enables the native-image tracing agent on `test`/`run`; `metadataCopy` folds the
    // agent's output into src/main/resources/META-INF/native-image (the committed seed), encoding
    // the agent → bundle → nativeCompile order M0 proved load-bearing.
    agent {
        defaultMode = "standard"
        metadataCopy {
            inputTaskNames.add("test")
            outputDirectories.add("src/main/resources/META-INF/native-image")
            mergeWithExisting = true
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Task-02 cross-jar fixture + native smoke.
//
// Answers the one open M0 question (M0-RESULTS "M1 requirements surfaced by Spike C" #3): Spike C
// proved only the *minimal* native resolve (reflection + source); it never ran the JarTypeSolver
// stretch. Here a tiny callee is compiled into a real jar, and the native `jcma` binary resolves a
// caller against it through JarTypeSolver — i.e. does agent-traced native-image metadata cover the
// jar-resolution surface, or is hand-edited reflection config needed? Nothing binary is committed:
// the jar is built to a known build-output path on demand.

val crossJarFixtureClasses by tasks.registering(JavaCompile::class) {
    description = "Compile the cross-jar fixture callee (crossjar.lib.Greeting)."
    source("src/test/resources/fixtures/engine/crossjar/lib")
    include("**/*.java")
    classpath = files()
    options.release = 25
    javaCompiler = javaToolchains.compilerFor {
        languageVersion = JavaLanguageVersion.of(25)
        vendor = JvmVendorSpec.GRAAL_VM
    }
    destinationDirectory = layout.buildDirectory.dir("fixtures/crossjar-lib-classes")
}

val crossJarFixtureJar by tasks.registering(Jar::class) {
    description = "Package the cross-jar fixture callee into a jar at a known build path."
    from(crossJarFixtureClasses)
    destinationDirectory = layout.buildDirectory.dir("fixtures")
    archiveFileName = "crossjar-lib.jar"
}

tasks.register("crossJarSmoke") {
    group = "verification"
    description = "Native cross-jar resolve smoke — does JarTypeSolver resolve under native-image?"
    dependsOn(tasks.named("nativeCompile"), crossJarFixtureJar)
    doLast {
        val binary = layout.buildDirectory.file("native/nativeCompile/jcma").get().asFile
        val jar = layout.buildDirectory.file("fixtures/crossjar-lib.jar").get().asFile
        require(binary.exists()) { "native binary missing (run nativeCompile): $binary" }
        require(jar.exists()) { "fixture jar missing: $jar" }

        // Stage a temp project: the caller App.java under standard layout, a pom (so the workspace
        // discovers it), and a cp.txt pointing only at the fixture jar — the callee lives nowhere
        // else, so a successful resolve is necessarily through JarTypeSolver.
        val proj = layout.buildDirectory.dir("fixtures/crossjar-project").get().asFile
        proj.deleteRecursively()
        val appDir = proj.resolve("src/main/java/crossjar/app").apply { mkdirs() }
        val appDst = appDir.resolve("App.java")
        file("src/test/resources/fixtures/engine/crossjar/app/crossjar/app/App.java")
            .copyTo(appDst, overwrite = true)
        proj.resolve("pom.xml").writeText(
            """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>jcma.fixture</groupId>
              <artifactId>crossjar-app</artifactId>
              <version>0.0.1</version>
            </project>
            """.trimIndent()
        )
        proj.resolve("cp.txt").writeText(jar.absolutePath)

        // App.java line 12 col 22: `        String s = g.hello("world");` — the `hello` call.
        val proc = ProcessBuilder(binary.absolutePath, "resolve", appDst.absolutePath, "12:22")
            .redirectErrorStream(true).start()
        val output = proc.inputStream.bufferedReader().readText()
        val code = proc.waitFor()
        println(output)
        val expected = "crossjar.lib.Greeting.hello(java.lang.String)"
        if (code != 0 || !output.contains(expected)) {
            throw GradleException(
                "cross-jar smoke FAILED (exit=$code) — expected `$expected` via JarTypeSolver:\n$output")
        }
        println("cross-jar smoke OK — JarTypeSolver resolves through the native image")
    }
}
