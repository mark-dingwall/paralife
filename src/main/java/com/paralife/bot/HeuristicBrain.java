package com.paralife.bot;

import com.paralife.codec.ActiveEffect;
import com.paralife.codec.CellEntry;
import com.paralife.codec.Coord;
import com.paralife.codec.Frame;
import com.paralife.codec.KindData;
import com.paralife.engine.Direction;
import com.paralife.world.Entity.ParticleType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;

/**
 * Pure-function priority-based heuristic brain. Keyed off {@link BotState} +
 * {@link Frame.TickFrame}. No per-instance mutable state.
 *
 * <p><b>Phase 15 (plan 15-09) refactor:</b>
 * <ul>
 *   <li>{@link #decide(Frame.TickFrame, BotState, Random)} replaces
 *       {@code decide(Perception)}. Inputs are the decoded wire frame +
 *       {@link BotState} + a caller-owned {@link Random} (enables
 *       deterministic tests).</li>
 *   <li>Dead-branch bug fixed (Phase 09 tech debt #3): {@code predatorType}
 *       is {@code myType.predator()} unconditionally — the previous ternary
 *       had identical arms.</li>
 *   <li>Scope: SOLO / BONDED_PRIMARY / COMPOSITE_MEMBER(LOCOMOTOR) only.
 *       BONDED_SECONDARY and authority-lite / passive composite roles return
 *       null; the server auto-fallback covers them. Authority-lite client-side
 *       target-choice (FEEDER/ATTACKER/REPRODUCER) is DEFERRED post-MVP per
 *       SCHEMA §7 scope note.</li>
 *   <li>LOCOMOTOR path emits {@code a|V|<3 numpad chars>} per SCHEMA §8.6
 *       rather than {@code a|M}.</li>
 * </ul>
 *
 * <p>Priority cascade (solo / bonded primary):
 * <ol>
 *   <li>FLEEING effect ({@code fF:<expiry>:<XXYY>}) — move away from strike.</li>
 *   <li>Adjacent predator — flee to non-predator empty cell.</li>
 *   <li>Adjacent prey — chase (highest-priority target wins).</li>
 *   <li>Adjacent nutrient — consume (verb E).</li>
 *   <li>Energy ≥ {@link #REPRODUCE_THRESHOLD} with non-toxic empty adjacent — reproduce.</li>
 *   <li>Random-walk fallback.</li>
 * </ol>
 */
public class HeuristicBrain {

    private static final Logger log = LoggerFactory.getLogger(HeuristicBrain.class);

    /** Default energy threshold above which the bot tries to reproduce. */
    public static final int REPRODUCE_THRESHOLD = 70;

    /** D-39 bit 0 (0x01): entity is STARVING. */
    static final int ENTITY_STATUS_STARVING = 0x01;
    /** D-39 bit 1 (0x02): entity is MUTATING (infection active). */
    static final int ENTITY_STATUS_MUTATING = 0x02;
    /** D-39 bit 2 (0x04): entity carries an active survivor buff. */
    static final int ENTITY_STATUS_BUFFED = 0x04;

    /** D-38 bit 1 (0x02): cell has TOXIN_PRESENT above threshold. */
    static final int CELL_STATUS_TOXIN_PRESENT = 0x02;
    /** D-38 bit 2 (0x04): cell is a MUTAGEN_ZONE. */
    static final int CELL_STATUS_MUTAGEN_ZONE = 0x04;

    /**
     * Fraction of maxEnergy below which the bot treats TOXIN cells as no-go.
     */
    public static final double TOXIC_AVOIDANCE_ENERGY_FRACTION = 0.30;

    /** Numpad digits corresponding to the 8 compass directions (excludes '5' = self). */
    private static final char[] NUMPAD_WALK = {'1', '2', '3', '4', '6', '7', '8', '9'};

    private final int reproduceThreshold;

    public HeuristicBrain(int reproduceThreshold) {
        this.reproduceThreshold = reproduceThreshold;
    }

    /**
     * Pure-function decision. Returns null when this bot has no client-side
     * action to submit this tick:
     * <ul>
     *   <li>BONDED_SECONDARY — primary decides.</li>
     *   <li>COMPOSITE_MEMBER with role 1..5 — authority-lite client-side brain
     *       is DEFERRED post-MVP; server auto-fallback covers.</li>
     * </ul>
     */
    public Frame.ActionFrame decide(Frame.TickFrame frame, BotState state, Random rng) {
        switch (state.embodiment()) {
            case BONDED_SECONDARY:
                return null;
            case COMPOSITE_MEMBER: {
                Integer role = state.compositeRole();
                if (role == null || role != 0) {
                    // FEEDER/ATTACKER/REPRODUCER/DEFENDER/SENSOR — deferred post-MVP.
                    return null;
                }
                return decideLocomotor(frame, state, rng);
            }
            case SOLO:
            case BONDED_PRIMARY:
            default:
                return decideFullAuthority(frame, state, rng);
        }
    }

    // ================================================================
    // Full-authority branch (SOLO / BONDED_PRIMARY) — M/E/A/R verbs
    // ================================================================

    /** Priority cascade: flee (effect) → flee (adjacent predator) → chase → consume → reproduce → walk. */
    private Frame.ActionFrame decideFullAuthority(Frame.TickFrame frame, BotState state, Random rng) {
        // 1. FLEEING effect with abs strike ctx — move directly away.
        Optional<ActiveEffect> fleeing = frame.effects().stream()
                .filter(e -> e.code() == 'F').findFirst();
        if (fleeing.isPresent() && fleeing.get().ctx().isPresent()) {
            int[] strike = fleeing.get().ctx().get();
            Direction away = awayFromStrike(frame.curX(), frame.curY(), strike[0], strike[1]);
            if (away != null) {
                return new Frame.ActionFrame('M',
                        Optional.of(String.valueOf(Direction.numpadOf(away))));
            }
        }

        // Scan the vision cells into typed lists.
        ParticleType myType = speciesToParticleType(state.species());
        ParticleType preyType = myType.prey();
        ParticleType predatorType = myType.predator();  // DEAD BRANCH FIX (Phase 09 #3)

        boolean lowEnergy = frame.maxEnergy() > 0
                && frame.energy() < TOXIC_AVOIDANCE_ENERGY_FRACTION * frame.maxEnergy();

        List<DirectionInfo> predators = new ArrayList<>();
        List<ScoredTarget> prey = new ArrayList<>();
        List<DirectionInfo> nutrients = new ArrayList<>();
        List<DirectionInfo> emptyCells = new ArrayList<>();

        for (CellEntry cell : frame.cells()) {
            DirectionInfo di = toDirectionInfo(cell);
            if (di == null) continue;  // non-adjacent / non-expressible direction

            int cellStatus = cell.envState().orElse(0);
            int entityStatus = cell.entityState().orElse(0);
            boolean toxic = (cellStatus & CELL_STATUS_TOXIN_PRESENT) != 0;

            Optional<Character> kindChar = simpleKindCode(cell);
            if (kindChar.isEmpty()) {
                // Env-only cell → treat as empty (passable, env may be toxic).
                if (!(lowEnergy && toxic)) {
                    emptyCells.add(di);
                }
                continue;
            }
            char kc = kindChar.get();
            if (kc == 'F') {
                if (!(lowEnergy && toxic)) {
                    nutrients.add(di);
                }
            } else if (kc == speciesOf(preyType)) {
                // Prey target.
                int priority = -di.distance;
                if ((entityStatus & ENTITY_STATUS_STARVING) != 0) priority += 2;
                if ((entityStatus & ENTITY_STATUS_MUTATING) != 0) priority -= 1;
                if ((entityStatus & ENTITY_STATUS_BUFFED) != 0) priority -= 1;
                prey.add(new ScoredTarget(di.direction, di.distance, priority));
            } else if (kc == speciesOf(predatorType)) {
                predators.add(di);
            }
            // Rock / bonded-other / composite members — no action target.
        }

        // 2. Adjacent-predator flee.
        List<DirectionInfo> adjPredators = adjacent(predators, 1);
        if (!adjPredators.isEmpty()) {
            Direction fleeDir = fleeDirection(adjPredators, emptyCells, rng);
            if (fleeDir != null) {
                return new Frame.ActionFrame('M',
                        Optional.of(String.valueOf(Direction.numpadOf(fleeDir))));
            }
        }

        // 3. Chase best adjacent (within 2) prey.
        List<ScoredTarget> closePrey = prey.stream()
                .filter(t -> t.distance <= 2)
                .toList();
        if (!closePrey.isEmpty()) {
            Direction chaseDir = closePrey.stream()
                    .max((a, b) -> Integer.compare(a.priority, b.priority))
                    .map(t -> t.direction).orElse(null);
            if (chaseDir != null) {
                // Verb A (attack) when prey is immediately adjacent (distance 1);
                // otherwise M (move closer).
                boolean adj = closePrey.stream().anyMatch(t -> t.distance == 1 && t.direction == chaseDir);
                char verb = adj ? 'A' : 'M';
                return new Frame.ActionFrame(verb,
                        Optional.of(String.valueOf(Direction.numpadOf(chaseDir))));
            }
        }

        // 4. Consume adjacent nutrient (verb E + numpad toward nutrient).
        List<DirectionInfo> adjNutrients = adjacent(nutrients, 1);
        if (!adjNutrients.isEmpty()) {
            Direction eatDir = adjNutrients.get(0).direction;
            return new Frame.ActionFrame('E',
                    Optional.of(String.valueOf(Direction.numpadOf(eatDir))));
        }

        // 5. Reproduce when energy is high and an adjacent empty exists.
        if (frame.energy() >= reproduceThreshold) {
            List<DirectionInfo> adjEmpty = adjacent(emptyCells, 1);
            if (!adjEmpty.isEmpty()) {
                Direction repDir = adjEmpty.get(rng.nextInt(adjEmpty.size())).direction;
                return new Frame.ActionFrame('R',
                        Optional.of(String.valueOf(Direction.numpadOf(repDir))));
            }
        }

        // 6. Move toward nearby nutrient (not adjacent).
        if (!nutrients.isEmpty()) {
            Direction nDir = nutrients.stream()
                    .min((a, b) -> Integer.compare(a.distance, b.distance))
                    .map(d -> d.direction).orElse(null);
            if (nDir != null) {
                return new Frame.ActionFrame('M',
                        Optional.of(String.valueOf(Direction.numpadOf(nDir))));
            }
        }

        // 7. Random walk.
        List<DirectionInfo> adjEmpty = adjacent(emptyCells, 1);
        if (!adjEmpty.isEmpty()) {
            Direction walkDir = adjEmpty.get(rng.nextInt(adjEmpty.size())).direction;
            return new Frame.ActionFrame('M',
                    Optional.of(String.valueOf(Direction.numpadOf(walkDir))));
        }

        // 8. No empty neighbours visible → random-walk numpad pick (server may
        // reject; BOTs don't rest verbally in the new protocol).
        char numpad = NUMPAD_WALK[rng.nextInt(NUMPAD_WALK.length)];
        return new Frame.ActionFrame('M', Optional.of(String.valueOf(numpad)));
    }

    // ================================================================
    // LOCOMOTOR vote branch — SCHEMA §8.6 `a|V|<3-char numpad>`
    // ================================================================

    private Frame.ActionFrame decideLocomotor(Frame.TickFrame frame, BotState state, Random rng) {
        // For Phase 15, simplified LOCOMOTOR voting: top-3 numpad digits from
        // the walk pool shuffled via the injected rng. Real-world direction
        // scoring would consult composite roster + pool, but authority-lite
        // voting heuristics are out of scope for this phase.
        char[] ranks = topThreeDirections(rng);
        return new Frame.ActionFrame('V',
                Optional.of(String.valueOf(ranks[0]) + ranks[1] + ranks[2]));
    }

    private static char[] topThreeDirections(Random rng) {
        char[] pool = NUMPAD_WALK.clone();
        // Partial Fisher-Yates — surface the top 3 slots via rng.
        for (int i = 0; i < 3; i++) {
            int j = i + rng.nextInt(pool.length - i);
            char tmp = pool[i];
            pool[i] = pool[j];
            pool[j] = tmp;
        }
        return new char[]{pool[0], pool[1], pool[2]};
    }

    // ================================================================
    // Helpers
    // ================================================================

    /**
     * Direction pointing away from {@code (strikeX, strikeY)} relative to
     * {@code (atX, atY)}. Both coords are absolute (0..4095) and compared by
     * linear delta (torus wrap-around in the fleeing cascade is a post-MVP
     * refinement — over 5x5 vision the nearest mirror of the strike is the
     * linear one).
     */
    static Direction awayFromStrike(int atX, int atY, int strikeX, int strikeY) {
        int dx = Integer.signum(atX - strikeX);
        int dy = Integer.signum(atY - strikeY);
        return fromDxDy(dx, dy);
    }

    static Direction fromDxDy(int dx, int dy) {
        if (dx == 0 && dy == 0) return null;
        for (Direction d : Direction.values()) {
            if (d.dx() == dx && d.dy() == dy) return d;
        }
        return null;
    }

    /** Extract a DirectionInfo from a CellEntry if its coord is expressible as a cardinal/diagonal direction. */
    private static DirectionInfo toDirectionInfo(CellEntry cell) {
        switch (cell.coord()) {
            case Coord.Numpad n -> {
                Direction d = Direction.fromNumpad(n.digit());
                if (d == null) return null;
                return new DirectionInfo(d, 1);
            }
            case Coord.Relative r -> {
                if (r.dx() == 0 && r.dy() == 0) return null;
                Direction d = fromDxDy(Integer.signum(r.dx()), Integer.signum(r.dy()));
                if (d == null) return null;
                int dist = Math.max(Math.abs(r.dx()), Math.abs(r.dy()));  // Chebyshev
                return new DirectionInfo(d, dist);
            }
            case Coord.Absolute ignored -> {
                // Env supplement coords in absolute form aren't direction-expressible
                // without the self position; brain skips.
                return null;
            }
        }
    }

    /** Extract the single-char kind code from a CellEntry, if it has a Simple kind (not a rock). */
    private static Optional<Character> simpleKindCode(CellEntry cell) {
        if (cell.kind().isEmpty()) return Optional.empty();
        KindData kd = cell.kind().get();
        if (kd instanceof KindData.Simple s) return Optional.of(s.code());
        return Optional.empty();
    }

    private static List<DirectionInfo> adjacent(List<DirectionInfo> list, int maxDist) {
        return list.stream().filter(d -> d.distance <= maxDist).toList();
    }

    /** Pick an adjacent empty cell whose direction does not appear among adjacent predators. */
    private static Direction fleeDirection(List<DirectionInfo> predators,
                                            List<DirectionInfo> emptyCells, Random rng) {
        List<DirectionInfo> adjEmpty = adjacent(emptyCells, 1);
        if (adjEmpty.isEmpty()) return null;
        for (DirectionInfo cell : adjEmpty) {
            boolean overlap = predators.stream().anyMatch(p -> p.direction == cell.direction);
            if (!overlap) return cell.direction;
        }
        // All directions overlap a predator — pick one at random.
        return adjEmpty.get(rng.nextInt(adjEmpty.size())).direction;
    }

    /** Wire-kind char for a ParticleType (matches SCHEMA §8.1 — C/M/S). */
    private static char speciesOf(ParticleType type) {
        return switch (type) {
            case CATALYST -> 'C';
            case MEMBRANE -> 'M';
            case SPORE -> 'S';
        };
    }

    private static ParticleType speciesToParticleType(char species) {
        return switch (species) {
            case 'C' -> ParticleType.CATALYST;
            case 'M' -> ParticleType.MEMBRANE;
            case 'S' -> ParticleType.SPORE;
            default -> throw new IllegalArgumentException("bad species: " + species);
        };
    }

    /** Adjacent-offset scoring tuple for nutrients / empties / predators. */
    private record DirectionInfo(Direction direction, int distance) {}

    /** Prey target with observable-only priority score (higher = more attractive). */
    private record ScoredTarget(Direction direction, int distance, int priority) {}
}
