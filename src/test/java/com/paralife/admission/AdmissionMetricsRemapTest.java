package com.paralife.admission;

import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Phase 19.1 D-08 — unit tests for {@link AdmissionMetrics#remapBucketTags}.
 *
 * <p>Pins: happy-path transfer, idempotent edge cases (null args, same-id),
 * and no-phantom-entry when the old id has no snapshot.
 */
class AdmissionMetricsRemapTest {

    private AdmissionMetrics metrics;

    @BeforeEach
    void setUp() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AdmissionConfig admissionConfig = AdmissionConfig.defaults();
        com.paralife.engine.TickEngine tickEngine = mock(com.paralife.engine.TickEngine.class);
        when(tickEngine.currentTick()).thenReturn(0L);
        AttributionTagger tagger = new AttributionTagger(64, tickEngine);
        metrics = new AdmissionMetrics(registry, admissionConfig, tickEngine, tagger);
    }

    @Test
    @DisplayName("happy path: Tags move from oldId to newId; oldId entry removed")
    void remapBucketTags_movesTagsToNewId() {
        // Simulate a bucket-tag snapshot being captured (lookupBucketTags reads the CHM directly).
        // Inject via the same incActiveBucket path that captures the snapshot in production.
        // Use the internal accessor to pre-populate the map for isolation.
        var session = mock(org.springframework.web.socket.WebSocketSession.class);
        var attrs = new java.util.HashMap<String, Object>();
        attrs.put("source", "unit-test");
        // "entityId" is the ATTR_ENTITY_ID key used by incActiveBucket to populate the snapshot.
        attrs.put("entityId", "entity-old");
        when(session.getAttributes()).thenReturn(attrs);
        when(session.getId()).thenReturn("s-old");

        // incActiveBucket captures entityId → Tags into bucketTagsByEntityId.
        metrics.incActiveBucket(session);

        Tags before = metrics.lookupBucketTags("entity-old");
        assertThat(before).isNotNull();

        metrics.remapBucketTags("entity-old", "entity-new");

        assertThat(metrics.lookupBucketTags("entity-old"))
                .as("old id must be removed after remap")
                .isNull();
        assertThat(metrics.lookupBucketTags("entity-new"))
                .as("new id must hold the same Tags object")
                .isSameAs(before);
    }

    @Test
    @DisplayName("idempotent: null args and same-id no-op without NPE")
    void remapBucketTags_nullAndSameIdNoOp() {
        // Should not throw
        metrics.remapBucketTags(null, "x");
        metrics.remapBucketTags("x", null);
        metrics.remapBucketTags("a", "a");
    }

    @Test
    @DisplayName("no-entry: remapping nonexistent id does not create phantom entry under newId")
    void remapBucketTags_noEntryDoesNotCreatePhantom() {
        metrics.remapBucketTags("ghost", "new-ghost");

        assertThat(metrics.lookupBucketTags("new-ghost"))
                .as("no phantom entry must be created for a nonexistent oldId")
                .isNull();
    }
}
