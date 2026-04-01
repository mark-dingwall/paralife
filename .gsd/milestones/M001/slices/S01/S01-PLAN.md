# S01: Spring Boot Project Scaffold

**Goal:** Fully functional Spring Boot 3.x application with Java 21, virtual threads, WebSocket dependencies, and health endpoint.
**Demo:** `./gradlew bootRun` starts the server, `curl localhost:8080/actuator/health` returns `{"status":"UP"}`.

## Must-Haves
- `./gradlew bootRun` starts without errors
- Virtual threads enabled (`spring.threads.virtual.enabled=true`)
- `/actuator/health` returns 200 with `{"status":"UP"}`
- WebSocket dependency present (used in later slices)
- Java 21 source/target configured
- Tests pass with `./gradlew test`

## Tasks

- [ ] **T01: Gradle project + Spring Boot application**
  Initialize Gradle Kotlin DSL project, add Spring Boot plugin, create main application class, application.yml with virtual threads, and verify it boots.

- [ ] **T02: Verify virtual threads & health endpoint**
  Add Actuator dependency, configure health endpoint, write a smoke test confirming boot + health + virtual threads active.

## Files Likely Touched
- `build.gradle.kts`
- `settings.gradle.kts`
- `src/main/java/com/paralife/ParalifeApplication.java`
- `src/main/resources/application.yml`
- `src/test/java/com/paralife/ParalifeApplicationTest.java`
