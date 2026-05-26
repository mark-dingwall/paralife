# PR #2 change surface — `git diff main...feat/diagnostics-instrumentation`

merge-base `14e96ea`; HEAD includes Phase-A framing commit `b0a97d2`. 5 files / +175/-2.

```diff
diff --git a/src/main/java/com/paralife/diagnostics/DeathDiagnostics.java b/src/main/java/com/paralife/diagnostics/DeathDiagnostics.java
new file mode 100644
index 0000000..1ce3a49
--- /dev/null
+++ b/src/main/java/com/paralife/diagnostics/DeathDiagnostics.java
@@ -0,0 +1,110 @@
+package com.paralife.diagnostics;
+
+import com.paralife.engine.TickEngine;
+import io.micrometer.core.instrument.Counter;
+import io.micrometer.core.instrument.MeterRegistry;
+import org.slf4j.Logger;
+import org.slf4j.LoggerFactory;
+import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
+import org.springframework.stereotype.Component;
+
+import java.util.Map;
+import java.util.concurrent.ConcurrentHashMap;
+import java.util.concurrent.atomic.LongAdder;
+
+/**
+ * Death-cause + lifespan diagnostic instrumentation. First resident of
+ * {@code com.paralife.diagnostics} — the home for flag-gated runtime
+ * instrumentation, distinct from the external profiling scripts/artifacts
+ * kept under {@code .planning/.../profiles}.
+ *
+ * <p>Attributes entity death cause and lifespan to answer: at scale, do entities
+ * die from starvation (food deficit), combat (RPS), overcrowding, or environment
+ * (toxin/mutagen/lightning)? The production death sweep ({@code
+ * SimulationEngine.processDeaths}) only sees {@code !isAlive()} (energy==0) and
+ * loses the cause — so each energy-sink site tags a <em>lethal hint</em> here;
+ * energy decay is the default (STARVATION) when no other site claimed the kill.
+ *
+ * <p>Gated by {@code paralife.diagnostics.death-trace.enabled=true}. When the
+ * flag is off the bean is absent and every call site's {@code null} guard makes
+ * this zero-cost — safe to leave in mainline. Origin: Phase 20 viability
+ * investigation (food-deficit death-treadmill, 2026-05-25); retained to support
+ * the deferred Population Viability &amp; Energy Balance work.
+ *
+ * <p>Birth is recorded at the single {@code LiveEntityRegistry.register}
+ * chokepoint (covers register + reproduction + budding); death at {@code
+ * DeathFinalizer}. Lethal hints are only emitted on the rare tick an entity
+ * actually crosses to energy==0, so hot-path overhead is negligible.
+ */
+@Component
+@ConditionalOnProperty(name = "paralife.diagnostics.death-trace.enabled", havingValue = "true")
+public class DeathDiagnostics {
+
+    private static final Logger log = LoggerFactory.getLogger(DeathDiagnostics.class);
+
+    public enum Cause { STARVATION, COMBAT, OVERCROWDING, TOXIN, MUTAGEN, LIGHTNING, UNKNOWN }
+
+    private final TickEngine tickEngine;
+    private final MeterRegistry meterRegistry;
+
+    private final Map<String, Long> birthTick = new ConcurrentHashMap<>();
+    private final Map<String, Cause> lethalHint = new ConcurrentHashMap<>();
+    private final Map<String, Integer> preHitEnergy = new ConcurrentHashMap<>();
+    private final Map<Cause, LongAdder> causeCounts = new ConcurrentHashMap<>();
+
+    public DeathDiagnostics(TickEngine tickEngine, MeterRegistry meterRegistry) {
+        this.tickEngine = tickEngine;
+        this.meterRegistry = meterRegistry;
+        log.warn("DeathDiagnostics ENABLED — flag-gated death-cause/lifespan trace ACTIVE. "
+                + "Disable in production (paralife.diagnostics.death-trace.enabled).");
+    }
+
+    /** Birth chokepoint — record spawn tick. Called from LiveEntityRegistry.register. */
+    public void recordBirth(String entityId) {
+        birthTick.put(entityId, tickEngine.currentTick());
+    }
+
+    /**
+     * Tag the cause that drove this entity to energy==0. Call AFTER the lethal
+     * energy write, only when the entity is now {@code !isAlive()}. First claim
+     * wins for the tick (combat before the decay sweep, etc.).
+     *
+     * @param preHit energy immediately BEFORE the lethal hit (for healthy-kill detection)
+     */
+    public void hintLethal(String entityId, Cause cause, int preHit) {
+        lethalHint.putIfAbsent(entityId, cause);
+        preHitEnergy.putIfAbsent(entityId, preHit);
+    }
+
+    /**
+     * Death finalised — emit the lifecycle record and bump the cause counter.
+     * Default cause is STARVATION (no site claimed it → energy decay outran food).
+     */
+    public void recordDeath(String entityId, String type) {
+        Cause cause = lethalHint.getOrDefault(entityId, Cause.STARVATION);
+        Long birth = birthTick.remove(entityId);
+        Integer preHit = preHitEnergy.remove(entityId);
+        lethalHint.remove(entityId);
+
+        long now = tickEngine.currentTick();
+        long lifespan = (birth != null) ? (now - birth) : -1L;
+
+        causeCounts.computeIfAbsent(cause, c -> new LongAdder()).increment();
+        Counter.builder("paralife.diag.deaths")
+                .tag("cause", cause.name().toLowerCase())
+                .tag("type", type)
+                .register(meterRegistry)
+                .increment();
+
+        // One line per death — `grep DEATH-TRACE` to pull the lifecycle sample.
+        log.info("DEATH-TRACE id={} type={} cause={} lifespanTicks={} preHitEnergy={} deathTick={}",
+                entityId, type, cause, lifespan, preHit, now);
+    }
+
+    /** Snapshot of cumulative cause histogram — for the periodic/final summary. */
+    public Map<Cause, Long> histogram() {
+        Map<Cause, Long> out = new ConcurrentHashMap<>();
+        causeCounts.forEach((c, adder) -> out.put(c, adder.sum()));
+        return out;
+    }
+}
diff --git a/src/main/java/com/paralife/engine/DeathFinalizer.java b/src/main/java/com/paralife/engine/DeathFinalizer.java
index 41a7074..30348e0 100644
--- a/src/main/java/com/paralife/engine/DeathFinalizer.java
+++ b/src/main/java/com/paralife/engine/DeathFinalizer.java
@@ -78,6 +78,14 @@ public class DeathFinalizer {
         this.liveEntityRegistry = liveEntityRegistry;
     }
 
+    /** Optional death-cause diagnostic (flag-gated). Null when the bean is absent. */
+    private com.paralife.diagnostics.DeathDiagnostics deathDiagnostics;
+
+    @Autowired(required = false)
+    public void setDeathDiagnostics(com.paralife.diagnostics.DeathDiagnostics deathDiagnostics) {
+        this.deathDiagnostics = deathDiagnostics;
+    }
+
     /**
      * Plan 14-06 Task 1: monotonic counter of death-finalize events. Increments
      * at the TOP of each finalize* method BEFORE collaborator calls, so the
@@ -109,6 +117,8 @@ public class DeathFinalizer {
     public void finalizeParticleDeath(int x, int y, Particle p) {
         deathEventCount++;
         String id = p.id();
+        // Flag-gated death diagnostic: attribute cause + lifespan before cleanup wipes state.
+        if (deathDiagnostics != null) deathDiagnostics.recordDeath(id, p.type().name());
         botRegistry.unregisterByEntity(id);
         // Phase 19 SCALE-07 (REVIEWS H3): unregister from LiveEntityRegistry immediately after BotRegistry.
         if (liveEntityRegistry != null) liveEntityRegistry.unregister(id);
@@ -131,6 +141,9 @@ public class DeathFinalizer {
         String primaryId = bp.primaryEntityId();
         String secondaryId = bp.secondaryEntityId();
 
+        // Flag-gated death diagnostic: bonded pairs occupy the grid under bp.id().
+        if (deathDiagnostics != null) deathDiagnostics.recordDeath(bp.id(), "BONDED");
+
         botRegistry.unregisterByEntity(primaryId);
         // Phase 19 SCALE-07 (REVIEWS H3): symmetry unregister for child ids (idempotent if absent).
         if (liveEntityRegistry != null) liveEntityRegistry.unregister(primaryId);
diff --git a/src/main/java/com/paralife/engine/EnvironmentEngine.java b/src/main/java/com/paralife/engine/EnvironmentEngine.java
index cfc4da6..6d75943 100644
--- a/src/main/java/com/paralife/engine/EnvironmentEngine.java
+++ b/src/main/java/com/paralife/engine/EnvironmentEngine.java
@@ -1269,6 +1269,21 @@ public class EnvironmentEngine implements EnvCleanupHooksBean.CompostSink {
         envDamageAppliedThisTick = true;
     }
 
+    /** Flag-gated death diagnostic: best-effort env sub-cause from persistent shadow grids. */
+    private com.paralife.diagnostics.DeathDiagnostics.Cause envCauseAt(int x, int y) {
+        if ((toxinGrid[x][y] & 0xFF) > 0) return com.paralife.diagnostics.DeathDiagnostics.Cause.TOXIN;
+        if ((mutagenGrid[x][y] & 0xFF) > 0) return com.paralife.diagnostics.DeathDiagnostics.Cause.MUTAGEN;
+        return com.paralife.diagnostics.DeathDiagnostics.Cause.LIGHTNING; // transient strike — no persistent grid
+    }
+
+    /** Optional death-cause diagnostic (flag-gated). Null when the bean is absent. */
+    private com.paralife.diagnostics.DeathDiagnostics deathDiagnostics;
+
+    @org.springframework.beans.factory.annotation.Autowired(required = false)
+    public void setDeathDiagnostics(com.paralife.diagnostics.DeathDiagnostics deathDiagnostics) {
+        this.deathDiagnostics = deathDiagnostics;
+    }
+
     public void processEnvDeaths() {
         if (!envDamageAppliedThisTick) return;
 
@@ -1282,8 +1297,12 @@ public class EnvironmentEngine implements EnvCleanupHooksBean.CompostSink {
                 Entity occupant = cell.occupant();
                 if (occupant == null) continue;
                 if (occupant instanceof Particle p && !p.isAlive()) {
+                    // Flag-gated death diagnostic: env sweep runs @Order(14) AFTER the
+                    // @Order(10) decay/combat sweep, so anything here died from env damage.
+                    if (deathDiagnostics != null) deathDiagnostics.hintLethal(p.id(), envCauseAt(x, y), 0);
                     deathFinalizer.finalizeParticleDeath(x, y, p);
                 } else if (occupant instanceof BondedPair bp && !bp.isAlive()) {
+                    if (deathDiagnostics != null) deathDiagnostics.hintLethal(bp.id(), envCauseAt(x, y), 0);
                     deathFinalizer.finalizeBondedPairDeath(x, y, bp);
                 } else if (occupant instanceof CompositeMember cm && !cm.isAlive()) {
                     deathFinalizer.finalizeCompositeMemberDeath(x, y, cm, processedComposites);
diff --git a/src/main/java/com/paralife/engine/LiveEntityRegistry.java b/src/main/java/com/paralife/engine/LiveEntityRegistry.java
index 4e3ecbd..6baf7f2 100644
--- a/src/main/java/com/paralife/engine/LiveEntityRegistry.java
+++ b/src/main/java/com/paralife/engine/LiveEntityRegistry.java
@@ -1,9 +1,11 @@
 package com.paralife.engine;
 
+import com.paralife.diagnostics.DeathDiagnostics;
 import com.paralife.world.GridConfig;
 import com.paralife.world.Position;
 import org.slf4j.Logger;
 import org.slf4j.LoggerFactory;
+import org.springframework.beans.factory.annotation.Autowired;
 import org.springframework.stereotype.Component;
 
 import java.util.ArrayList;
@@ -84,6 +86,14 @@ public class LiveEntityRegistry {
         this.rowMajorComparator = Comparator.comparingInt(this::rowMajorIndex);
     }
 
+    /** Optional death-cause diagnostic (flag-gated). Null when the bean is absent. */
+    private DeathDiagnostics deathDiagnostics;
+
+    @Autowired(required = false)
+    public void setDeathDiagnostics(DeathDiagnostics deathDiagnostics) {
+        this.deathDiagnostics = deathDiagnostics;
+    }
+
     /**
      * Register an entity. Idempotent on identical inputs; throws
      * {@link IllegalStateException} on conflict. REVIEWS MEDIUM-3.
@@ -116,6 +126,8 @@ public class LiveEntityRegistry {
         }
         indexById.put(entityId, dense.size());
         dense.add(new EntityEntry(entityId, position));
+        // Flag-gated lifespan diagnostic: single birth chokepoint (register + reproduce + bud).
+        if (deathDiagnostics != null) deathDiagnostics.recordBirth(entityId);
     }
 
     /** Row-major linear index: position().x() * height + position().y(). REVIEWS HIGH-1. */
diff --git a/src/main/java/com/paralife/engine/SimulationEngine.java b/src/main/java/com/paralife/engine/SimulationEngine.java
index 18e1e7f..385983e 100644
--- a/src/main/java/com/paralife/engine/SimulationEngine.java
+++ b/src/main/java/com/paralife/engine/SimulationEngine.java
@@ -294,6 +294,14 @@ public class SimulationEngine {
         this.entityLifecycleListener = entityLifecycleListener;
     }
 
+    /** Optional death-cause diagnostic (flag-gated). Null when the bean is absent. */
+    private com.paralife.diagnostics.DeathDiagnostics deathDiagnostics;
+
+    @Autowired(required = false)
+    public void setDeathDiagnostics(com.paralife.diagnostics.DeathDiagnostics deathDiagnostics) {
+        this.deathDiagnostics = deathDiagnostics;
+    }
+
     /**
      * Phase 19 SCALE-07: returns a row-major-sorted entity snapshot for per-entity
      * iteration. When {@link #liveEntityRegistry} is injected (Spring production path),
@@ -872,9 +880,14 @@ public class SimulationEngine {
     private void applyDeltaToOccupant(Position pos, int energyDelta) {
         Cell c = worldGrid.getCell(pos.x(), pos.y());
         if (c.occupant() instanceof Particle p) {
+            // Flag-gated death diagnostic: negative delta crossing to 0 = combat/splash kill.
+            if (deathDiagnostics != null && energyDelta < 0 && p.energy() + energyDelta <= 0)
+                deathDiagnostics.hintLethal(p.id(), com.paralife.diagnostics.DeathDiagnostics.Cause.COMBAT, p.energy());
             worldGrid.setEntity(pos.x(), pos.y(),
                     p.withEnergy(p.energy() + energyDelta));
         } else if (c.occupant() instanceof Entity.BondedPair bp) {
+            if (deathDiagnostics != null && energyDelta < 0 && bp.energy() + energyDelta <= 0)
+                deathDiagnostics.hintLethal(bp.id(), com.paralife.diagnostics.DeathDiagnostics.Cause.COMBAT, bp.energy());
             worldGrid.setEntity(pos.x(), pos.y(),
                     bp.withEnergy(bp.energy() + energyDelta));
         } else if (c.occupant() instanceof Entity.CompositeMember cm) {
@@ -1071,10 +1084,16 @@ public class SimulationEngine {
             }
 
             if (neighborCount >= config.overcrowdingThreshold()) {
+                int penalty = config.overcrowdingEnergyPenalty();
                 if (occupant instanceof Particle p) {
-                    worldGrid.setEntity(x, y, p.withEnergy(p.energy() - config.overcrowdingEnergyPenalty()));
+                    // Flag-gated death diagnostic: overcrowding penalty crossing to 0.
+                    if (deathDiagnostics != null && p.energy() - penalty <= 0)
+                        deathDiagnostics.hintLethal(p.id(), com.paralife.diagnostics.DeathDiagnostics.Cause.OVERCROWDING, p.energy());
+                    worldGrid.setEntity(x, y, p.withEnergy(p.energy() - penalty));
                 } else if (occupant instanceof Entity.BondedPair bp) {
-                    worldGrid.setEntity(x, y, bp.withEnergy(bp.energy() - config.overcrowdingEnergyPenalty()));
+                    if (deathDiagnostics != null && bp.energy() - penalty <= 0)
+                        deathDiagnostics.hintLethal(bp.id(), com.paralife.diagnostics.DeathDiagnostics.Cause.OVERCROWDING, bp.energy());
+                    worldGrid.setEntity(x, y, bp.withEnergy(bp.energy() - penalty));
                 }
                 if (!cell.hasFlag(Cell.FLAG_OVERCROWDED)) {
                     worldGrid.setCell(x, y, worldGrid.getCell(x, y).withAddedFlag(Cell.FLAG_OVERCROWDED));
```
