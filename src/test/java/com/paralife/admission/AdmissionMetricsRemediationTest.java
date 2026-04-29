package com.paralife.admission;

import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.paralife.admission.AdmissionMetricsLifecycleTest.FakeSession;
import static com.paralife.admission.AdmissionMetricsLifecycleTest.harnessSession;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit-level coverage for the P18-Chunk-A remediation set:
 *
 * <ul>
 *   <li><b>C2</b> — {@code releaseBucketTags} drops the entityId snapshot so
 *       {@code bucketTagsByEntityId} does not grow unbounded across churn.</li>
 *   <li><b>H1</b> — active gauge accounting under rebind-across-harness-change uses the
 *       snapshot tags captured at admission, so the dec lands on the original bucket even
 *       when the rebound session presents different attribution headers.</li>
 *   <li><b>Invariant</b> — across mixed register/stall/rebind/release sequences the
 *       sum of bucket gauges stays non-negative and the snapshot map shrinks back to zero
 *       after every entity is fully reaped.</li>
 * </ul>
 */
class AdmissionMetricsRemediationTest {

    private SimpleMeterRegistry registry;
    private AdmissionMetrics metrics;
    private AttributionTagger tagger;

    @BeforeEach
    void setup() {
        registry = new SimpleMeterRegistry();
        com.paralife.engine.TickEngine tickEngine = mock(com.paralife.engine.TickEngine.class);
        when(tickEngine.currentTick()).thenReturn(0L);
        tagger = new AttributionTagger(64, tickEngine);
        metrics = new AdmissionMetrics(registry, AdmissionConfig.defaults(), tickEngine, tagger);
    }

    // ── C2 — releaseBucketTags drops the entityId snapshot ───────────────────

    @Test
    void releaseBucketTags_dropsSnapshotForEntityId() {
        FakeSession s = harnessSession("h-1", "entity-1");
        metrics.incActiveBucket(s);
        assertThat(metrics.lookupBucketTags("entity-1")).isNotNull();

        metrics.releaseBucketTags("entity-1");

        assertThat(metrics.lookupBucketTags("entity-1")).isNull();
    }

    @Test
    void releaseBucketTags_isIdempotentAndNullSafe() {
        metrics.releaseBucketTags(null);
        metrics.releaseBucketTags("never-existed");
        // No throw, no state. Second call must also be safe.
        FakeSession s = harnessSession("h-1", "entity-1");
        metrics.incActiveBucket(s);
        metrics.releaseBucketTags("entity-1");
        metrics.releaseBucketTags("entity-1");

        assertThat(metrics.bucketTagsSize()).isZero();
    }

    @Test
    void hundredRegisterReleaseCycles_holdSnapshotMapAtZero() {
        // Without releaseBucketTags this map grows unbounded across churn.
        for (int i = 0; i < 100; i++) {
            String entityId = "entity-" + i;
            FakeSession s = harnessSession("h-" + (i % 4), entityId);
            metrics.incActiveBucket(s);
            metrics.decActiveBucket(s);
            metrics.releaseBucketTags(entityId);
        }
        assertThat(metrics.bucketTagsSize()).isZero();
    }

    // ── H1 — snapshot-based active dec under rebind-across-harness-change ────

    @Test
    void rebindAcrossHarnessChange_decViaSnapshotKeepsOriginalBucketAccurate() {
        // Original Allow on harness=h1, snapshot captured.
        FakeSession original = harnessSession("h1", "entity-A");
        metrics.incActiveBucket(original);

        Tags h1Tags = Tags.of("source", "harness", "harness", "h1");
        Tags h2Tags = Tags.of("source", "harness", "harness", "h2");

        assertActiveValue(h1Tags, 1.0);

        // Rebind: stalled bucket drop via snapshot, active stays incremented.
        Tags snapshot = metrics.lookupBucketTags("entity-A");
        assertThat(snapshot).isEqualTo(h1Tags);

        // New session arrives with different harness id (operator restart, auto-uuid).
        FakeSession rebound = harnessSession("h2", "entity-A");

        // Cleanup eventually fires. The H1 fix path calls decActiveBucketByTags(snapshot),
        // NOT decActiveBucket(rebound) — so the dec lands on h1, not h2.
        Tags reboundSnapshot = metrics.lookupBucketTags("entity-A");
        assertThat(reboundSnapshot).isEqualTo(h1Tags);
        metrics.decActiveBucketByTags(reboundSnapshot);
        metrics.releaseBucketTags("entity-A");

        // h1 bucket: 1 inc, 1 dec via snapshot → 0. h2 bucket: never touched.
        assertActiveValue(h1Tags, 0.0);
        assertGaugeAbsent(h2Tags);
        // Confirm the broken path: if cleanup had used live session tags (h2), the bucket
        // would now read -1. Verify the fix never produces a negative gauge.
        assertThat(metrics.minActiveBucketCount()).isGreaterThanOrEqualTo(0);
    }

    // ── Invariant — gauges non-negative + snapshot map flushes to zero ──────

    @Test
    void mixedScenarioInvariants_holdAcrossManyEntities() {
        // Scenario sequence per entity: register (incActive) → stall (incStalled)
        // → rebind (decStalled via snapshot) → close (decActive via snapshot, releaseBucketTags).
        for (int i = 0; i < 50; i++) {
            String entityId = "e" + i;
            FakeSession s = harnessSession("h" + (i % 8), entityId);
            metrics.incActiveBucket(s);
            metrics.incStalledBucket(s, entityId);
            Tags snap = metrics.lookupBucketTags(entityId);
            metrics.decStalledBucketByTags(snap);
            metrics.decActiveBucketByTags(snap);
            metrics.releaseBucketTags(entityId);
        }
        assertThat(metrics.totalActiveBucketCount()).isZero();
        assertThat(metrics.totalStalledBucketCount()).isZero();
        assertThat(metrics.minActiveBucketCount()).isGreaterThanOrEqualTo(0);
        assertThat(metrics.minStalledBucketCount()).isGreaterThanOrEqualTo(0);
        assertThat(metrics.bucketTagsSize()).isZero();
    }

    @Test
    void harnessInvariant_anyHarnessTagImpliesSourceHarness() {
        // Build a few buckets across the source taxonomy.
        FakeSession harnessA = harnessSession("h-1", "e1");
        metrics.incActiveBucket(harnessA);
        FakeSession harnessB = harnessSession("h-2", "e2");
        metrics.incStalledBucket(harnessB, "e2");

        FakeSession unknown = new FakeSession();
        unknown.attrs().put(AttributionTagger.ATTR_SOURCE, "unknown");
        unknown.attrs().put(AdmissionMetrics.ATTR_ENTITY_ID, "e3");
        metrics.incActiveBucket(unknown);

        // Walk every active and stalled bucket key. Harness tag IFF source=harness.
        for (Tags key : metrics.activeBucketKeys()) {
            assertHarnessImpliesSource(key);
        }
        for (Tags key : metrics.stalledBucketKeys()) {
            assertHarnessImpliesSource(key);
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private void assertActiveValue(Tags tags, double expected) {
        io.micrometer.core.instrument.Gauge g = registry.find(AdmissionMetrics.M_ACTIVE_ENTITIES)
                .tags(tags)
                .gauge();
        if (expected == 0.0 && g == null) return;
        assertThat(g).as("active gauge for " + tags).isNotNull();
        assertThat(g.value()).isEqualTo(expected);
    }

    private void assertGaugeAbsent(Tags tags) {
        io.micrometer.core.instrument.Gauge g = registry.find(AdmissionMetrics.M_ACTIVE_ENTITIES)
                .tags(tags)
                .gauge();
        assertThat(g).as("gauge for " + tags + " should not exist").isNull();
    }

    private static void assertHarnessImpliesSource(Tags key) {
        String source = null;
        boolean hasHarness = false;
        for (io.micrometer.core.instrument.Tag t : key) {
            if ("source".equals(t.getKey())) source = t.getValue();
            if ("harness".equals(t.getKey())) hasHarness = true;
        }
        if (hasHarness) {
            assertThat(source)
                    .as("Tags %s carry harness; source must be 'harness'", key)
                    .isEqualTo("harness");
        }
    }
}
