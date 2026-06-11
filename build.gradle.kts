plugins {
    java
    jacoco
    id("org.springframework.boot") version "3.4.4"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.paralife"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
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
//     LoadTest, MetabolismIntegrationTest, all composite-* tests.
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
    // Phase C (integration-test resource-leak audit) lets us safely remove it.
    forkEvery = 1
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
// This deliberately does NOT touch the pinned `test` task (invariant I-04: forkEvery=1
// must stay until the P22.1 exit gate passes). It is a measurement harness, not a config change.
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
    // Default probe set: 6 classes → 6 distinct cached contexts (each a distinct
    // @TestPropertySource). Override with -PprobeClasses=Simple1,Simple2 for smoke runs.
    val probeClasses = (project.findProperty("probeClasses") as String?)
            ?: "HundredBotIntegrationTest,StallRecoveryIntegrationTest,MetabolismIntegrationTest," +
               "WebSocketIntegrationTest,PerceptionActionIntegrationTest,BotFleetTest"

    // Neutralise the two inherited `tasks.withType<Test>` settings that would corrupt the probe:
    //   1. forkEvery=1  → set 0 so all contexts share ONE JVM (the whole point).
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
