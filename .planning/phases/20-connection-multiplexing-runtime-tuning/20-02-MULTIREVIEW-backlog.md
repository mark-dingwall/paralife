# Plan 20-02 multi-review backlog (pass 1, 2026-06-03)

Items deferred from the convergence loop — real gaps but additive coverage, not contract violations.

## TD-20-02-A — Fallback test does not exercise Spring property binding

**Surfaced:** codex MEDIUM (multi-review pass 1, 2026-06-03).

**Finding:** `JettyIdleTimeoutFallbackTest` constructs `JettyRuntimeConfig` directly and passes a primitive legacy value into the helper. It cannot catch a broken `@Value("${paralife.websocket.idle-timeout-ms:60000}")`, a property-source precedence regression, or accidental omission of the helper call from `jettyRequestUpgradeStrategy`. The helper's pure logic is well-covered (5 cases A–E), but the observable end-to-end fallback path is not.

**Why deferred:** The helper *is* the bug surface (case-E proxy was the actual review concern). `BindingRoundTripTest` already exercises Spring binding for new keys. Adding 5 more `@SpringBootTest` cases for the legacy path adds ~30–60s suite time for a one-phase back-compat fallback that 999.x removes entirely.

**Fix sketch:** Add a `@Nested @SpringBootTest @TestPropertySource` per case to `JettyIdleTimeoutFallbackTest` that injects `JettyDeflateCustomizer` (or `JettyRequestUpgradeStrategy`) and asserts the resolved idle timeout via reflection on the bound bean.

---

## TD-20-02-B — No test observes resulting Jetty Configurable state

**Surfaced:** codex MEDIUM (multi-review pass 1, 2026-06-03).

**Finding:** Wiring (`JettyDeflateCustomizer:97-106`) chains all 8 `Configurable` setters once each from the record. If a future edit silently drops `setMaxFrameSize(runtimeConfig.maxFrameSize())` (etc.), no targeted test fails — only the full-context deflate tests (`ServerRefusesUpgradeWithoutDeflateTest`, `WebSocketDeflateHandshakeIntegrationTest`) catch it, and only if their assertions happen to exercise the dropped setter's effect.

**Why deferred:** Jetty 12's `Configurable` is a callback consumed during upgrade; the bound configuration is not directly readable without a mock/spy or a custom test `WebSocketCreator`. The full-context tests cover the path end-to-end. Cost-benefit borderline.

**Fix sketch:** Test that calls `jettyRequestUpgradeStrategy(JettyRuntimeConfig.defaults(), 60000L)`, captures the `Consumer<Configurable>` registered via `addWebSocketConfigurer` (reflection), invokes it against a Mockito mock `Configurable`, and verifies each of the 8 setters is invoked exactly once with the expected argument.
