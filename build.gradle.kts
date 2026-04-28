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
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
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
    useJUnitPlatform {
        // Phase 16 Plan 03: @Tag("slow") tests are opt-in via -PincludeLong=true.
        // Default `./gradlew test` excludes slow tests (16-06 long-run emergence
        // stability). `./gradlew test -PincludeLong=true` includes all tags.
        if (project.findProperty("includeLong") != "true") {
            excludeTags("slow")
        }
    }
    // Phase 17: when slow load tests are included, fork a fresh JVM per test class.
    // EmergenceStabilityLoadTest, LoadTest, and HundredBotIntegrationTest each
    // bring up a full Spring context, 100-bot fleet, and Jetty server. Sharing a
    // JVM caused thread pool / native socket / context proliferation, degrading
    // downstream tests (98/100 sessions in isolation → 75/100 after siblings).
    if (project.findProperty("includeLong") == "true") {
        forkEvery = 1
        maxParallelForks = 1
    }
    finalizedBy(tasks.jacocoTestReport)
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required = true
        html.required = true
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
