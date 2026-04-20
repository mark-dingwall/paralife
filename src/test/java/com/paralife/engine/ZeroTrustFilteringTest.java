package com.paralife.engine;

import com.paralife.codec.Frame;
import com.paralife.codec.PerceptionCodec;
import com.paralife.websocket.TickBroadcaster;
import com.paralife.world.Entity.BondedPair;
import com.paralife.world.Entity.Particle;
import com.paralife.world.Entity.ParticleType;
import com.paralife.world.Position;
import com.paralife.world.WorldGrid;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Plan 15-08 Task 3: SCHEMA §8.1 zero-trust guarantees.
 *
 * <ul>
 *   <li>No entity id strings appear in any encoded T frame (D-28, T-15-03).</li>
 *   <li>BondedPair neighbours reveal only the primary kind (D/N/T);
 *       secondary type is hidden.</li>
 *   <li>Self cell at bot's position is never emitted in the s block (D-08).</li>
 * </ul>
 *
 * <p>Assertions use regex anchored to cell-entry boundaries (coord + presence
 * + kind) per SCHEMA §8.1 grammar — NOT coarse substring matches on single
 * kind chars, which would false-positive on tick IDs, expiry ticks, or any
 * base64 char that happens to equal a kind code. Per review feedback
 * 2026-04-20 (Claude LOW #c).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
        "paralife.tick.auto-start=false",
        "paralife.world.width=16",
        "paralife.world.height=16"
})
class ZeroTrustFilteringTest {

    @Autowired TickBroadcaster broadcaster;
    @Autowired WorldGrid worldGrid;
    @Autowired BotRegistry botRegistry;

    /**
     * Cell-entry boundary regex: matches the presence + kind chars of any
     * {@code s}-block entry. Anchored to valid cell-entry start patterns
     * (numpad digit OR signed relative coord) so the kind char matches ONLY
     * inside actual cell entries, never in a tick ID or expiry tick value.
     *
     * <p>Group 1 captures presence (1|2|3); Group 2 captures the kind char
     * when presence bit 0 is set. Base64 digits inside the relative coord use
     * the Base64 alphabet which includes {@code 0-9A-Za-z_-}; the kind char
     * group is restricted to the SCHEMA §8.1.1 code set.
     */
    private static final Pattern CELL_ENTRY_KIND = Pattern.compile(
            "(?:[1-9]|[+\\-][0-9A-Za-z_\\-][+\\-][0-9A-Za-z_\\-])"
                    + "([1-3])"
                    + "([CMSDNT0-9RF])?");

    @BeforeEach
    void wipeWorld() {
        // Clear any occupants/bots left from an earlier test class sharing
        // the Spring context. Worldgrid here is a fresh 16×16 per this
        // test class's @TestPropertySource override but bot registry and
        // world state persist across tests in the same context.
        int w = worldGrid.getWidth();
        int h = worldGrid.getHeight();
        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) {
                if (worldGrid.getCell(x, y).hasOccupant()) {
                    worldGrid.setEntity(x, y, null);
                }
            }
        }
        botRegistry.clear();
    }

    @Test
    void encodedFrameCarriesNoEntityIds() {
        String botId = registerBot("test-s1", 5, 5, ParticleType.CATALYST);
        String bondedId = placeBondedPair(5, 6, ParticleType.CATALYST, ParticleType.SPORE);

        Frame.TickFrame frame = broadcaster.buildTickFrame(
                botRegistry.getBySession("test-s1").orElseThrow(), 1L);
        String wire = PerceptionCodec.encode(frame);

        assertFalse(wire.contains(botId), "Wire must not contain own bot id: " + wire);
        assertFalse(wire.contains(bondedId), "Wire must not contain neighbour id: " + wire);
        assertFalse(wire.contains("SPORE"), "Wire must not reveal bonded secondary type as string");
    }

    @Test
    void bondedNeighbourEmitsOnlyPrimaryKindCode() {
        registerBot("test-s1", 5, 5, ParticleType.CATALYST);
        placeBondedPair(5, 6, ParticleType.CATALYST, ParticleType.SPORE);

        Frame.TickFrame frame = broadcaster.buildTickFrame(
                botRegistry.getBySession("test-s1").orElseThrow(), 1L);
        String wire = PerceptionCodec.encode(frame);

        // Extract the s block. Format: T|...|s<entry>,<entry>,...[|...]
        String sBlock = extractSBlock(wire);
        assertNotNull(sBlock, "Expected s block in wire: " + wire);

        // Walk cell entries via regex; confirm:
        //   (a) At least one entry has kind 'D' (bonded CAT primary).
        //   (b) NO entry has a kind char 'S' (bonded secondary would leak).
        //       The bonded secondary is a Spore — a solo 'S' kind for a
        //       neighbour cell would indicate the projector exposed the
        //       secondary type. Only the bonded-CAT-primary code 'D' is
        //       allowed for this neighbour.
        boolean sawBondedCatPrimary = false;
        Matcher m = CELL_ENTRY_KIND.matcher(sBlock);
        while (m.find()) {
            String kind = m.group(2);
            if (kind == null) continue;
            if (kind.equals("D")) sawBondedCatPrimary = true;
            assertFalse("S".equals(kind),
                    "Solo 'S' kind appeared in s block — indicates bonded-secondary leak: "
                            + sBlock);
        }
        assertTrue(sawBondedCatPrimary,
                "Expected at least one cell entry with bonded-CAT-primary kind 'D' in s block: "
                        + sBlock);
    }

    @Test
    void selfCellIsNeverEmittedInSBlock() {
        registerBot("test-s1", 5, 5, ParticleType.CATALYST);

        Frame.TickFrame frame = broadcaster.buildTickFrame(
                botRegistry.getBySession("test-s1").orElseThrow(), 1L);

        // Self is at (5,5) — its self-coord would be numpad '5' or relative
        // (0,0). SCHEMA §8.1 says self is never emitted. Walk the frame's
        // cell list — not the encoded string — so a zero-cells frame also
        // passes trivially.
        for (var cell : frame.cells()) {
            if (cell.coord() instanceof com.paralife.codec.Coord.Numpad n) {
                assertFalse(n.digit() == '5',
                        "Numpad '5' (self) must not appear in s block");
            }
            if (cell.coord() instanceof com.paralife.codec.Coord.Relative r) {
                assertFalse(r.dx() == 0 && r.dy() == 0,
                        "Relative (0,0) (self) must not appear in s block");
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────

    private String extractSBlock(String wire) {
        int i = wire.indexOf("|s");
        if (i < 0) return null;
        int end = wire.indexOf('|', i + 2);
        return end < 0 ? wire.substring(i + 2) : wire.substring(i + 2, end);
    }

    private String registerBot(String sessionId, int x, int y, ParticleType type) {
        String entityId = "test-bot-" + x + "-" + y;
        Particle p = Particle.spawn(entityId, type);
        worldGrid.setEntity(x, y, p);
        botRegistry.register(sessionId, entityId, new Position(x, y));
        return entityId;
    }

    private String placeBondedPair(int x, int y, ParticleType primary, ParticleType secondary) {
        String bondedId = "test-bond-" + x + "-" + y + "+secondary";
        BondedPair bp = new BondedPair(bondedId, primary, secondary, 80, 200);
        worldGrid.setEntity(x, y, bp);
        return bondedId;
    }
}
