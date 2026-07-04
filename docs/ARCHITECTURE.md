# Paralife — Architecture internals

Live design reference for the deeper subsystems. On-demand (not injected into every
session). The high-level map — package layers, tick pipeline, env-projection layers, the
wire bitmask contract — lives in `/CLAUDE.md` §Architecture; this doc holds the detailed
rationale that doesn't need to load every turn.

Canonical capability contracts referenced below:
`docs/SCHEMA.md` (wire protocol), `docs/ADMISSION.md` (admission/backpressure/resume-token FSM),
`docs/HARNESS.md` (load harness + connection model), `docs/RUNTIME.md` (per-connection tuning).

---

## Outbound concurrency (Phase 17, D-10)

Each connected WebSocket session is paired with one virtual thread that loops
`queue.take(); session.sendMessage(...)` over a per-session bounded
`ArrayBlockingQueue<Frame>` (capacity from `paralife.admission.backpressure.outbound-queue-size`).

**Why VT-per-session and not Jetty native async write:**
- Matches Paralife's stated philosophy — simple blocking code, virtual threads do concurrency.
- Per-session isolation is structural — one slow socket cannot block the tick thread or any other session.
- `queue.size()` is the explicit backpressure signal — observable as
  `paralife.backpressure.stalled.sessions` gauge and per-session via `OutboundSender.queueDepth(sessionId)`.
- Java 21 VTs scheduled on shared carriers; per-VT cost is a few KB heap. 1000+ VTs is acceptable.

When the queue overflows, the session transitions to STALLED:
- `OutboundSender.offer` invokes the overflow callback registered by `WorldWebSocketHandler`.
- `WorldWebSocketHandler.markStalled` removes `ATTR_ENTITY_ID`, sets `ATTR_STALL_TICK`,
  issues a resume token via `ResumeTokenRegistry.issue`, and detaches the sender VT.
- The next inbound frame from the stalled session receives `E|408|reconnect-required` and the WS is closed.
- The entity is held on the grid for `paralife.admission.backpressure.grace-window-ticks` ticks.
- If the client reconnects with `r|<species>|<resumeToken>` within the grace window, `AdmissionGate` consults
  `ResumeTokenRegistry.tryRebind` and re-binds the new session to the preserved entityId.

Synchronized-session-monitor contract: every writer to a session holds `synchronized(session)` for
the actual `sendMessage` call. Writers: drain VT (`OutboundSender.drainLoop`), keepalive PING
(`WebSocketKeepaliveService.onTick`), out-of-band stall/error frames
(`WorldWebSocketHandler.sendOutOfBand`), and the back-compat fallback in
`WorldWebSocketHandler.sendFrame`. Encoding and metric recording stay outside the monitor — the
monitor only protects the non-thread-safe `sendMessage` invocation.

**markStalled close-then-best-effort-OOB (Phase 19.1, D-07):** `WorldWebSocketHandler.markStalled`
invokes `OutboundSender.detachSession(WebSocketSession, CloseStatus.SERVICE_RESTARTED)` (the
close-aware overload with caller-supplied status), not the `String` overload. The transport-close
fires first, which causes any blocked Jetty write inside the drain VT's `synchronized(session)`
block to throw `IOException`, allowing the VT to exit cleanly. The OOB 408 frame that follows is
best-effort: `WorldWebSocketHandler.sendOutOfBand` carries an `isOpen()` guard (≈`WorldWebSocketHandler.java:1054`);
the close itself is the reconnect signal — OOB is not load-bearing. No second
`session.close(...)` is issued; the close-aware detach already carried
`SERVICE_RESTARTED` to the wire. The close itself is the reconnect signal — clients observing it
issue an `r|<species>|<resumeToken>` against the grace window. This trade-off is intentional: it
eliminates the tick-thread block that the previous `String`-overload path suffered when a slow
client kept the Jetty write blocked.

Full token taxonomy, STALLED FSM, and resume-token lifecycle: `docs/ADMISSION.md`.

## Connection model (Phase 18, D-05 / D-21)

**WS:entity 1:1** — one WebSocket connection per entity, always. Every entity on the grid has
exactly one WebSocket session; every WebSocket session owns exactly one entity during the Alive phase.

See `docs/HARNESS.md` §1 for full rationale, exception policy, and the 5 000-connections-per-JVM
design ceiling (D-02).

## Runtime tuning (Phase 20)

Per-connection overhead reduction at scale lives in `paralife.runtime.jetty.*` and
`paralife.runtime.app.*` `@ConfigurationProperties` records (Phase 20 D-07 layers
2 + 3, see `JettyRuntimeConfig` and `AppRuntimeConfig`). JVM flags ship as
documented per-scale-tier presets in `docs/RUNTIME.md` §3, NOT as wrapper scripts (D-08).

**The WS:entity 1:1 model from §Connection model is non-negotiable.** Tuning
reduces per-connection cost; it does not collapse connections. SCALE-08's "or
equivalent transport-level scale strategy" escape hatch was intentionally taken.
See `docs/RUNTIME.md` §1 for the full rationale and §3 for per-tier recipes.

The two metric gauges to watch when tuning are `paralife.tick.health.work-time-ms`
(`AdmissionMetrics.java:70`) and `paralife.outbound.detach.timeout`
(`AdmissionMetrics.java:79`, P19.1 D-18). Profile artifacts under
`.planning/phases/20-connection-multiplexing-runtime-tuning/profiles/` are pinned
to commit SHAs (e.g. `62c1b44` churn baseline, `103a615` active-scenario) for
reproducibility per D-19. Headline-gauge
values are sampled from `/actuator/metrics/{name}` into JSON sidecars at capture
time — `application.yml:15` exposes the `metrics` actuator
endpoint that Plan 1c + Plan 5 capture from.

D-20 keeps `paralife.admission.backpressure.outbound-queue-size` in
`AdmissionConfig` rather than moving it under `paralife.runtime.app.*`; namespace
consolidation is Phase 999.4. Codec hot-path opts (D-10, layer 4 of the tuning
surface) are JFR-driven and never cross the wire — `docs/SCHEMA.md` stays bit-exact.
