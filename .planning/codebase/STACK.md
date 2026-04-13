# Technology Stack

**Analysis Date:** 2026-04-12

## Languages

**Primary:**
- Java 21 - All source code, Spring Boot 3.4.4 application
- YAML - Configuration files

## Runtime

**Environment:**
- Java 21 (via toolchain configuration in `build.gradle.kts`)
- Spring Boot 3.4.4

**Package Manager:**
- Gradle 8.x (wrapper in project)
- Lockfile: Not applicable (Gradle dependency management via `build.gradle.kts`)

## Frameworks

**Core:**
- Spring Boot 3.4.4 - Application framework
  - `spring-boot-starter-web` - HTTP server and REST support
  - `spring-boot-starter-websocket` - WebSocket communication
  - `spring-boot-starter-actuator` - Health checks and metrics endpoints
- Spring Framework 6.x - Dependency injection, event publishing, configuration properties

**Testing:**
- JUnit 5 - Test framework
- Spring Boot Test - Integration testing support

**Build/Dev:**
- Gradle - Build automation
- JaCoCo - Code coverage reporting

## Key Dependencies

**Critical:**
- Jackson (transitive via Spring) - JSON serialization/deserialization
  - Used for WebSocket message marshalling in `com.paralife.websocket.Messages`
- Spring Web Socket - Text-based WebSocket handling for bot communication
- Virtual Thread support - Built into Java 21, configured via `spring.threads.virtual.enabled: true`

**Infrastructure:**
- SLF4J + Logback - Logging (transitive via Spring Boot)
- Gradle wrapper - Ensures consistent build environment

## Configuration

**Environment:**
- `src/main/resources/application.yml` - Single configuration file
  - Spring application name: "paralife"
  - Virtual threads enabled at JVM level
  - Server port: 8080
  - Actuator endpoints exposed: health, info, metrics

**Application Properties:**
```yaml
paralife:
  world:
    width: 256
    height: 256
  tick:
    interval-ms: 500
    auto-start: true
  simulation:
    energy-decay-per-tick: 1
    combat-energy-transfer: 10
    nutrient-spawn-probability: 0.001
    nutrient-consume-energy: 5
    enabled: true
```

Bound to configuration records:
- `GridConfig` (`paralife.world.*`) - `src/main/java/com/paralife/world/GridConfig.java`
- `TickConfig` (`paralife.tick.*`) - `src/main/java/com/paralife/engine/TickConfig.java`
- `SimulationConfig` (`paralife.simulation.*`) - `src/main/java/com/paralife/engine/SimulationConfig.java`

## Platform Requirements

**Development:**
- Java 21 JDK (required by toolchain declaration)
- Gradle (via wrapper, no local installation needed)

**Production:**
- Java 21 JRE minimum
- HTTP server capable of WebSocket connections (Spring Boot embedded Tomcat)
- Single-instance deployment sufficient for current architecture

---

*Stack analysis: 2026-04-12*
