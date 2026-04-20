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
    useJUnitPlatform()
    finalizedBy(tasks.jacocoTestReport)
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required = true
        html.required = true
    }
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
