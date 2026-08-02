plugins {
    java
    jacoco
    id("org.springframework.boot") version "3.4.4"
    id("io.spring.dependency-management") version "1.1.7"
    id("com.diffplug.spotless") version "6.25.0"
}

group = "com.paralife"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

// Lightweight formatting/hygiene gate (the absent lint signal flagged by the
// workflow-migration investigation). Native Spotless steps only — no opinionated
// Java reformatter, so existing style is preserved (CLAUDE.md: match existing style).
// `ratchetFrom` introduces it to the 1000-test corpus without a reformat-the-world
// commit: only files changed vs origin/main are checked; untouched legacy files are
// left until next edited. `spotlessCheck` auto-binds to `check`.
spotless {
    java {
        target("src/main/java/**/*.java", "src/test/java/**/*.java")
        ratchetFrom("origin/main")
        importOrder()
        trimTrailingWhitespace()
        endWithNewline()
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web") {
        exclude(group = "org.springframework.boot", module = "spring-boot-starter-tomcat")
    }
    implementation("org.springframework.boot:spring-boot-starter-websocket") {
        exclude(group = "org.springframework.boot", module = "spring-boot-starter-tomcat")
    }
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-jetty")

    // Plan 15-09: Jetty 12 native WebSocket client (for BotClient) — provides
    // permessage-deflate extension negotiation that Spring's StandardWebSocketClient
    // lacks (D-33 client-side enforcement). Pinned to Spring Boot 3.4.4's managed
    // Jetty 12.0.18; artifact not in Spring Boot's dependency management so
    // version is declared explicitly.
    implementation("org.eclipse.jetty.websocket:jetty-websocket-jetty-client:12.0.18")

    // Phase 18 (D-15 / D-16): Picocli for the LoadHarness CLI surface.
    implementation("info.picocli:picocli:4.7.7")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    // Promoted from testRuntimeOnly so the Phase 22.1 LeakCensusListener (a
    // platform TestExecutionListener) compiles against the launcher API.
    testImplementation("org.junit.platform:junit-platform-launcher")
}

// Plan 15-11 Task 4 removed the former `sourceSets.test.java.exclude(...)` block.
// The three excluded test classes (ActionResolverTest, CompositeIntegrationTest,
// VisionScopedOvercrowdingTest) were carrying stale `Messages.*` imports against
// removed wire records and the 8-arg (ObjectMapper) ActionResolver constructor
// that plan 15-06 deleted. Their coverage intent is preserved by sibling tests:
//   - ActionResolver: SimulationIntegrationTest, PerceptionActionIntegrationTest,
//     LoadTest, ActionResolverReproduceTest, ActionResolverConsumeTest,
//     all composite-* tests.
//   - Composite lifecycle: CompositeFormationTest, CompositeDissolutionTest,
//     CompositeEnergyDistributorTest, CompositeMovementTest, CompositeCombatTest,
//     CompositeRegistryTest.
//   - Vision-scoped overcrowding (computeVisionScopedOvercrowded): now covered
//     in-place by TickBroadcasterProjectionTest (plans 15-07/15-08).
// The three stale files were deleted alongside Messages.java in plan 15-11 Task 4.

tasks.withType<Test> {
    // The leakProbe task MUST include @Tag("slow") (StallRecoveryIntegrationTest is one of
    // its distinct cached contexts). An empty `useJUnitPlatform {}` in the leakProbe block
    // does NOT clear an already-configured excludeTags — JUnitPlatformOptions is cumulative
    // (verified on Gradle 8.14.2: --test-dry-run selected 5/6 probe classes, dropping the
    // slow one). So condition the exclusion at the source, on task name, rather than relying
    // on the probe task to undo it. (PR#3 review — codex HIGH.)
    val excludeSlow = name != "leakProbe" && project.findProperty("includeLong") != "true"
    useJUnitPlatform {
        // Phase 16 Plan 03: @Tag("slow") tests are opt-in via -PincludeLong=true.
        // Default `./gradlew test` excludes slow tests (16-06 long-run emergence
        // stability). `./gradlew test -PincludeLong=true` includes all tags.
        if (excludeSlow) {
            excludeTags("slow")
        }
    }
    // Fork a fresh JVM per test class to prevent inter-test contamination.
    // Originally added under -PincludeLong=true (Phase 17) for slow tests bringing up
    // full Spring contexts / Jetty / 100-bot fleets. 2026-05-03: an unbounded
    // `Thread.join()` in WorldGridTest.concurrentReadsDontBlock hung for 2h after
    // 497 leaked threads from earlier tests starved the virtual-thread carrier pool —
    // proving leaks now exist outside slow-tagged tests too. Made unconditional until
    // the integration-test resource-leak audit (Phase 22 / 22.1) could safely remove it.
    // 2026-06-17 (`876c8b1`): flipped to 0 so the whole suite shares ONE JVM. This shipped
    // as the leak-fix commit; the formal Phase 22.1 revalidation confirming the flip holds is
    // now recorded in .planning/STATE.md (TD-22-B, 2026-07-06). Closing three root causes made
    // the flip safe:
    // (A) WorldWebSocketHandler @PreDestroy close-aware mass-detach → 0 "did not exit" drain-VT
    // WARNs; (B) test client-stop hygiene (BlockingWebSocketClient self-clean +
    // register-before-connect) → 0 HttpClient/scheduler residue in the end-of-suite census;
    // (C) a real cleanupByEntityId→cleanupBot double-dec of the active-bucket gauge (surfaced
    // only under shared-JVM context reuse).
    // Empirical confidence (2026-06-27, branch claude/forkevery-test-flakiness): 44 back-to-back
    // forkEvery=0 full-suite runs (1013 tests) pinned to 2 cores via taskset to squeeze the VT
    // carrier pool. ZERO leak / VT-exit / "Could not write XML" signals across all 44 — the leak
    // fix holds. The only failures were three timing-fragile tests (all addressed): a test-logic
    // race (WorldWebSocketHandlerTest.respawnCapEnforced — fixed), a tight connect/settle timeout
    // (BotFleetTest — widened), and a load-throughput SLA starved below its compute floor by the
    // 2-core squeeze (LoadTest.hundredBotsNoCorruption — reclassified @Tag("slow"), out of the
    // default gate). The CI stress sweep (.github/workflows/stress.yml) reproduces the 2-core
    // squeeze on demand; the ci.yml gate runs at the runner's native core count.
    forkEvery = 0
    maxParallelForks = 1
    finalizedBy(tasks.jacocoTestReport)
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required = true
        html.required = true
    }
}

// Phase 22.1 cache-cap experiment (opt-in, throwaway probe).
// Runs a curated set of heavy RANDOM_PORT integration classes — each with a DISTINCT
// @TestPropertySource, hence a DISTINCT cached Spring context — together in ONE shared
// JVM (forkEvery=0), then LeakCensusListener dumps an end-of-suite platform-thread census.
//
// This deliberately does NOT touch the `test` task (which runs forkEvery=0 at HEAD — see the
// `forkEvery` comment above; the P22.1 revalidation confirming that flip holds is recorded in
// .planning/STATE.md). It is a measurement harness, not a config change.
//
//   ./gradlew leakProbe -PcacheMax=32 -Plabel=uncapped   # baseline (all contexts coexist)
//   ./gradlew leakProbe -PcacheMax=1  -Plabel=cap1        # forces eviction within the run
//
// Census written to build/leak-probe/census-<label>.txt (and echoed to stdout).
tasks.register<Test>("leakProbe") {
    group = "verification"
    description = "Phase 22.1 cache-cap thread census: heavy integration classes in one shared JVM."

    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath

    val cacheMax = (project.findProperty("cacheMax") as String?) ?: "32"
    val label = (project.findProperty("label") as String?) ?: "run"
    // Default probe set: 5 classes → 5 distinct cached contexts (each a distinct
    // @TestPropertySource). Override with -PprobeClasses=Simple1,Simple2 for smoke runs.
    val probeClasses = (project.findProperty("probeClasses") as String?)
            ?: "HundredBotIntegrationTest,StallRecoveryIntegrationTest," +
               "WebSocketIntegrationTest,PerceptionActionIntegrationTest,BotFleetTest"

    // Harden the probe against the inherited `tasks.withType<Test>` settings:
    //   1. forkEvery is already 0 above (`876c8b1`) — re-declared here explicitly so the
    //      shared-ONE-JVM invariant (the whole point) survives a future edit to the parent.
    //   2. jacoco finalizer → jacocoTestReport dependsOn(test), which would drag in the
    //      entire pinned suite. Clear it; this is a measurement run, not a coverage run.
    forkEvery = 0
    maxParallelForks = 1
    setFinalizedBy(emptyList<Any>())

    // Tag handling is sourced in the `tasks.withType<Test>` block above: it skips
    // excludeTags("slow") for this task by name, so StallRecovery (@Tag("slow")) IS
    // included here. An empty useJUnitPlatform {} cannot clear a cumulative exclude, so we
    // do NOT rely on that — this call only ensures the framework is set.
    useJUnitPlatform { }
    filter {
        isFailOnNoMatchingTests = false
        probeClasses.split(",").map { it.trim() }.filter { it.isNotEmpty() }.forEach {
            includeTestsMatching("*.$it")
        }
    }

    systemProperty("paralife.leakprobe", "1")
    systemProperty("paralife.leakprobe.label", label)
    systemProperty("spring.test.context.cache.maxSize", cacheMax)

    testLogging {
        showStandardStreams = true
        events("passed", "skipped", "failed")
    }
}

// M5-A observer: the renderer's pure geometry and painting modules are plain ES
// modules tested by Node's built-in runner (no npm dependencies, no browser).
//
// Two Node facts this task exists to work around:
//   1. `node --test src/test/js/` treats the directory as a module path and fails
//      with MODULE_NOT_FOUND — the supported form is the test-file glob, quoted so
//      Node (not the shell) expands it.
//   2. A glob matching NOTHING is a successful zero-test run (exit 0). So the gate
//      would silently pass if a test file were renamed or deleted. The preflight
//      below names the files that must exist; deleting one fails the build.
val requiredJsTests = listOf("observer-markers.test.js", "observer-render.test.js", "observer-legend.test.js")

tasks.register<Exec>("jsTest") {
    group = "verification"
    description = "Runs the observer renderer's JavaScript module tests under Node."

    doFirst {
        val jsTestDir = layout.projectDirectory.dir("src/test/js").asFile
        val missing = requiredJsTests.filterNot { jsTestDir.resolve(it).isFile }
        if (missing.isNotEmpty()) {
            throw GradleException(
                "Missing required JS test file(s): $missing. Node exits 0 on a zero-match " +
                    "glob, so this preflight is what keeps the gate from passing vacuously."
            )
        }
    }

    commandLine("node", "--test", "src/test/js/*.test.js")
}

tasks.named("check") { dependsOn("jsTest") }

// Phase 18 (D-15): loadHarnessJar — standalone load harness fat jar.
// Produces build/libs/paralife-*-load-harness.jar with main class LoadHarness.
// Invocation: java -jar build/libs/paralife-*-load-harness.jar --server-uri ws://... --count 200
//
// Uses BootJar so Spring Boot's nested-jar launcher handles classpath assembly.
// targetJavaVersion must be set explicitly on custom BootJar tasks — Spring Boot
// only sets it automatically on the named "bootJar" task.
tasks.register<org.springframework.boot.gradle.tasks.bundling.BootJar>("loadHarnessJar") {
    group = "application"
    description = "Build the standalone Paralife load harness fat jar (Phase 18 D-15). Task: loadHarnessJar."
    archiveClassifier.set("load-harness")
    mainClass.set("com.paralife.harness.LoadHarness")
    classpath = sourceSets["main"].runtimeClasspath
    targetJavaVersion.set(JavaVersion.VERSION_21)
}

// Phase 18 (D-15): runHarness — dev-iteration task for the load harness.
// Invocation: ./gradlew runHarness --args="--server-uri ws://localhost:8080/ws/world --count 10 --duration 30"
tasks.register<JavaExec>("runHarness") {
    group = "application"
    description = "Run the LoadHarness against a live server (dev iteration; Phase 18 D-15). Task: runHarness."
    mainClass.set("com.paralife.harness.LoadHarness")
    classpath = sourceSets["main"].runtimeClasspath
    standardInput = System.`in`
    environment(System.getenv().filterKeys { it.startsWith("PARALIFE_HARNESS_") })
    systemProperties = System.getProperties().entries
            .filter { (it.key as String).startsWith("paralife.") }
            .associate { it.key as String to it.value }
}

// Phase 15.1: operator CLI for launching bot clients against a running server.
// BotRunner enforces a 100-bot hard cap matching the v1.0/v2.0 validated envelope.
// Invocation: ./gradlew runBot --args="ws://localhost:8080/ws/world 100 60"
tasks.register<JavaExec>("runBot") {
    group = "application"
    description = "Launch N bot clients against a live server (Phase 15.1 operator CLI, 100-bot cap)"
    mainClass.set("com.paralife.bot.BotRunner")
    classpath = sourceSets["main"].runtimeClasspath
    standardInput = System.`in`
    // Forward any -Dparalife.* system properties set on the gradle invocation so UAT
    // runs can override bot-side JVM tunables if needed.
    systemProperties = System.getProperties()
            .entries
            .filter { (it.key as String).startsWith("paralife.") }
            .associate { it.key as String to it.value }
}
