package com.paralife.admission;

import com.paralife.engine.TickEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class ResumeTokenRegistryTest {

    private static final Pattern TOKEN_FORMAT = Pattern.compile("^r:[0-9a-f]{16}$");

    private AdmissionConfig admissionConfig;
    private SimpleMeterRegistry meterReg;
    private AdmissionMetrics metrics;
    private ResumeTokenRegistry registry;

    @BeforeEach
    void setup() {
        admissionConfig = new AdmissionConfig(
                256, false,
                AdmissionConfig.TickOverloadConfig.defaults(),
                new AdmissionConfig.BackpressureConfig(16, 5),   // grace=5
                AdmissionConfig.AttributionConfig.defaults());
        meterReg = new SimpleMeterRegistry();
        metrics = new AdmissionMetrics(meterReg);
        registry = new ResumeTokenRegistry(admissionConfig, metrics);
    }

    @Test
    void issueActiveMatchesFormatAndDoesNotIncrementGauge() {
        String token = registry.issueActive("entity-1", "session-1");
        assertThat(token).matches(TOKEN_FORMAT);
        assertThat(registry.size()).isEqualTo(1);
        assertThat(registry.stalledSize()).isEqualTo(0);
        assertThat(meterReg.get(AdmissionMetrics.M_STALLED_SESSIONS).gauge().value()).isEqualTo(0.0);
    }

    @Test
    void convertToStalledFlipsStateAndIncrementsGauge() {
        String t = registry.issueActive("entity-1", "session-1");
        registry.convertToStalled(t, 100L);
        assertThat(registry.peek(t)).isPresent();
        assertThat(registry.peek(t).get().state()).isEqualTo(ResumeTokenRegistry.State.STALLED);
        assertThat(registry.peek(t).get().expiresAtTick()).isEqualTo(105L);
        assertThat(registry.stalledSize()).isEqualTo(1);
        assertThat(meterReg.get(AdmissionMetrics.M_STALLED_SESSIONS).gauge().value()).isEqualTo(1.0);
    }

    @Test
    void convertToStalledOnUnknownTokenIsNoOp() {
        registry.convertToStalled("r:0000000000000000", 100L);
        assertThat(registry.size()).isEqualTo(0);
        assertThat(registry.stalledSize()).isEqualTo(0);
    }

    @Test
    void convertToStalledIsIdempotent() {
        String t = registry.issueActive("e1", "s1");
        registry.convertToStalled(t, 100L);
        long expBefore = registry.peek(t).get().expiresAtTick();
        registry.convertToStalled(t, 200L);   // second call — no transition
        assertThat(registry.peek(t).get().expiresAtTick()).isEqualTo(expBefore);
        assertThat(registry.stalledSize()).isEqualTo(1);   // gauge not double-counted
    }

    @Test
    void tryRebindRejectsActiveTokens() {
        String t = registry.issueActive("entity-1", "session-1");
        Optional<ResumeTokenRegistry.RebindOutcome> r = registry.tryRebind(t, "session-2", 102L);
        assertThat(r).isEmpty();
    }

    @Test
    void tryRebindOnStalledReturnsFreshActiveToken() {
        String oldToken = registry.issueActive("entity-1", "session-1");
        registry.convertToStalled(oldToken, 100L);
        Optional<ResumeTokenRegistry.RebindOutcome> result =
                registry.tryRebind(oldToken, "session-2", 102L);
        assertThat(result).isPresent();
        assertThat(result.get().entityId()).isEqualTo("entity-1");
        assertThat(result.get().freshResumeToken()).matches(TOKEN_FORMAT).isNotEqualTo(oldToken);
        assertThat(registry.contains(oldToken)).isFalse();
        ResumeTokenRegistry.ResumeEntry fresh = registry.peek(result.get().freshResumeToken()).orElseThrow();
        assertThat(fresh.state()).isEqualTo(ResumeTokenRegistry.State.ACTIVE);
        assertThat(registry.stalledSize()).isEqualTo(0);   // STALLED consumed; new token is ACTIVE
        assertThat(meterReg.get(AdmissionMetrics.M_STALLED_SESSIONS).gauge().value()).isEqualTo(0.0);
    }

    @Test
    void tryRebindUnknownReturnsEmpty() {
        Optional<ResumeTokenRegistry.RebindOutcome> r =
                registry.tryRebind("r:0000000000000000", "session-x", 100L);
        assertThat(r).isEmpty();
    }

    @Test
    void tryRebindNullReturnsEmpty() {
        assertThat(registry.tryRebind(null, "session-x", 100L)).isEmpty();
    }

    @Test
    void doubleRebindOfSameTokenFails() {
        String t = registry.issueActive("e1", "s1");
        registry.convertToStalled(t, 100L);
        Optional<ResumeTokenRegistry.RebindOutcome> first = registry.tryRebind(t, "s2", 102L);
        assertThat(first).isPresent();
        Optional<ResumeTokenRegistry.RebindOutcome> second = registry.tryRebind(t, "s3", 103L);
        assertThat(second).isEmpty();
    }

    @Test
    void tryRebindExpiredStalledReturnsEmpty() {
        String t = registry.issueActive("e1", "s1");
        registry.convertToStalled(t, 100L);   // expires at 105
        Optional<ResumeTokenRegistry.RebindOutcome> r = registry.tryRebind(t, "s2", 110L);
        assertThat(r).isEmpty();
    }

    @Test
    void sweepReapsOnlyStalledExpiredAndInvokesCallbackWithEntityId() {
        List<String> reaped = new ArrayList<>();
        registry.setCleanupCallback(reaped::add);

        String aliveToken = registry.issueActive("entity-alive", "s-alive");   // ACTIVE — never reaped
        String t1 = registry.issueActive("entity-1", "s1");
        String t2 = registry.issueActive("entity-2", "s2");
        String t3 = registry.issueActive("entity-3", "s3");
        registry.convertToStalled(t1, 100L);    // expires 105
        registry.convertToStalled(t2, 100L);    // expires 105
        registry.convertToStalled(t3, 110L);    // expires 115

        registry.onTick(new TickEvent(105L));    // sweep boundary inclusive: t1 + t2 reaped, t3 stays
        assertThat(reaped).containsExactlyInAnyOrder("entity-1", "entity-2");
        assertThat(registry.contains(aliveToken)).isTrue();   // ACTIVE never reaped
        assertThat(registry.contains(t3)).isTrue();
        assertThat(registry.stalledSize()).isEqualTo(1);
    }

    @Test
    void sweepDoesNotReapActiveEntries() {
        List<String> reaped = new ArrayList<>();
        registry.setCleanupCallback(reaped::add);
        registry.issueActive("e-alive", "s-alive");
        registry.onTick(new TickEvent(1_000_000L));
        assertThat(reaped).isEmpty();
    }

    @Test
    void sweepWithoutCallbackStillRemovesEntries() {
        String t = registry.issueActive("e1", "s1");
        registry.convertToStalled(t, 100L);
        registry.onTick(new TickEvent(110L));
        assertThat(registry.size()).isEqualTo(0);
        assertThat(registry.stalledSize()).isEqualTo(0);
    }

    @Test
    void clearActiveRemovesActiveEntryForEntity() {
        String t = registry.issueActive("e1", "s1");
        registry.clearActive("e1");
        assertThat(registry.contains(t)).isFalse();
    }

    @Test
    void clearActiveDoesNotTouchStalledEntry() {
        String t = registry.issueActive("e1", "s1");
        registry.convertToStalled(t, 100L);
        registry.clearActive("e1");           // clearActive only removes ACTIVE entries
        assertThat(registry.contains(t)).isTrue();
        assertThat(registry.peek(t).get().state()).isEqualTo(ResumeTokenRegistry.State.STALLED);
    }

    @Test
    void issuedTokensAreUnique() {
        String t1 = registry.issueActive("e1", "s1");
        String t2 = registry.issueActive("e2", "s2");
        assertThat(t1).isNotEqualTo(t2);
    }

    @Test
    void gaugeFiltersToStalledOnly() {
        registry.issueActive("e1", "s1");
        registry.issueActive("e2", "s2");
        registry.issueActive("e3", "s3");
        assertThat(meterReg.get(AdmissionMetrics.M_STALLED_SESSIONS).gauge().value()).isEqualTo(0.0);
        // Stall one of them
        String t4 = registry.issueActive("e4", "s4");
        registry.convertToStalled(t4, 100L);
        assertThat(meterReg.get(AdmissionMetrics.M_STALLED_SESSIONS).gauge().value()).isEqualTo(1.0);
        assertThat(registry.size()).isEqualTo(4);   // 4 entries total, only 1 STALLED
    }
}
