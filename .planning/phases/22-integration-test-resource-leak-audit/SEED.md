# Phase 22 — Integration Test Resource Leak Audit (Seed Notes)

> Informal hand-off from the troubleshooting session that triggered this phase.
> `/gsd-discuss-phase 22` will turn this into proper CONTEXT.md.

## Trigger incident (2026-05-03)

`./gradlew test` hung for 1h 59m on `WorldGridTest.concurrentReadsDontBlock` before being killed.

`jstack` of the test executor JVM showed:
- 497 live threads (Spring contexts, Jetty `QueuedThreadPool` runners, leaked VTs from prior tests).
- Test worker parked on `Thread.join` of 100 reader virtual threads inside `WorldGrid.getCell(5,5)`.
- Reader VTs never scheduled — diagnosis: ForkJoinPool carrier starvation from earlier tests pinning carriers (`synchronized` blocks in Spring/Jetty teardown paths).

Order-dependent flake: WorldGridTest itself is fine in isolation; only fails after the heavyweight integration tests have polluted the shared JVM.

## Quick fixes already shipped

| File | Change |
|------|--------|
| `src/test/java/com/paralife/world/WorldGridTest.java` | `Thread.join()` → `Thread.join(Duration.ofSeconds(10))` with explicit failure message naming carrier starvation |
| `src/test/resources/junit-platform.properties` | Default 5-min timeout per test method, `SEPARATE_THREAD` mode so parked threads do get interrupted |
| `build.gradle.kts` | `forkEvery = 1` / `maxParallelForks = 1` made unconditional (was `-PincludeLong=true` only). Comment updated. |

These keep CI honest but cost wall-clock. Goal of this phase is to make them removable.

## Scope

1. Audit every `*IntegrationTest.java` and `@SpringBootTest` class:
   - Spring contexts closed via `@DirtiesContext` or explicit `@AfterEach` close.
   - WebSocket sessions opened in `@BeforeEach`/`setup` are closed in `@AfterEach`.
   - `OutboundSender.shutdown()` / queue-drain awaited at session close, not GC.
   - Jetty `QueuedThreadPool` instances either reused or shut down between cases.
2. Resolve TD-19.5-A: `OutboundSender.awaitAllSessionQueuesDrained` VT race + `GoldenTraceEquivalenceTest` flake (~40% in isolated runs, masked in suite).
3. Once leaks are fixed, remove `forkEvery = 1` / `maxParallelForks = 1` from `build.gradle.kts`.
4. Re-baseline CI wall time; should land within 10% of pre-2026-05-03.

## Success criteria

- Full `./gradlew test` runs in a single shared JVM with **<100 live threads** at end of suite.
- No test class hangs >5 minutes (already enforced by `junit-platform.properties`).
- `forkEvery=1` removed from `build.gradle.kts`.
- TD-19.5-A closed.

## Likely suspects (entry points to investigate)

- `OutboundSender` — per-session VTs on `ArrayBlockingQueue<Frame>`. Cleanup path on session close.
- `WorldWebSocketHandler` — slow-client / stalled-session detach path; `ResumeTokenRegistry` lifecycle.
- `WebSocketKeepaliveService` — keepalive PING scheduler thread.
- Any test instantiating `Server` (Jetty) directly without `server.stop()` in teardown.
- Any test using `@SpringBootTest` without `@DirtiesContext` where it spins up Jetty + tick engine + bot fleet.

## Cross-references

- Build comment in `build.gradle.kts` line ~68 documents the trigger.
- TD-19.5-A in `.planning/STATE.md` deferred items table.
- Phase 17 already learned this lesson once for slow-tagged tests; same lesson now applies project-wide.
