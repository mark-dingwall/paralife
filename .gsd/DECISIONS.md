# Decisions Register

<!-- Append-only. Never edit or remove existing rows.
     To reverse a decision, add a new row that supersedes it.
     Read this file at the start of any planning or research phase. -->

| # | When | Scope | Decision | Choice | Rationale | Revisable? |
|---|------|-------|----------|--------|-----------|------------|
| D001 | M001 | arch | Client-server connectivity | WebSocket | Persistent connections, server pushes tick events, natural fit for real-time simulation. | Yes |
| D002 | M001 | arch | Concurrency model | Virtual Threads (Java 21+, Project Loom) | Modern approach, massive concurrency with simple blocking code. Avoids reactive complexity while achieving similar throughput. | Yes |
| D003 | M001 | arch | World grid topology | 2D toroidal grid (wraps at edges) | Classic CA structure, naturally partitionable for horizontal scaling, proven model for neighbor queries. | Yes |
| D004 | M001 | library | Build system | Gradle with Kotlin DSL | Industry standard for Spring Boot, better IDE support than Maven for modern projects. | No |
| D005 | M001 | arch | Spring WebSocket approach | `WebSocketHandler` (not STOMP) | Raw handler gives full control over message protocol, avoids STOMP overhead. Can add STOMP later if needed. | Yes — if message routing complexity grows |
