package com.paralife.bot;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BotStateTest {

    @Test
    void initialStartsSoloWithGivenSpecies() {
        BotState s = BotState.initial('C');
        assertEquals('C', s.species());
        assertEquals(BotState.Embodiment.SOLO, s.embodiment());
        assertNull(s.compositeRole());
        assertTrue(s.hasFullAuthority());
    }

    @Test
    void withChangeCodeC_movesToBondedPrimary() {
        BotState s = BotState.initial('S').withChangeCode('C');
        assertEquals('S', s.species(), "species preserved across c-block transitions");
        assertEquals(BotState.Embodiment.BONDED_PRIMARY, s.embodiment());
        assertNull(s.compositeRole());
        assertTrue(s.hasFullAuthority());
    }

    @Test
    void withChangeCodeD_movesToBondedSecondary() {
        BotState s = BotState.initial('M').withChangeCode('D');
        assertEquals('M', s.species());
        assertEquals(BotState.Embodiment.BONDED_SECONDARY, s.embodiment());
        assertNull(s.compositeRole());
        assertFalse(s.hasFullAuthority(), "bonded secondary is passive");
    }

    @Test
    void withChangeCodeZ_returnsToSolo() {
        BotState s = BotState.initial('C').withChangeCode('C').withChangeCode('Z');
        assertEquals(BotState.Embodiment.SOLO, s.embodiment());
        assertNull(s.compositeRole());
        assertEquals('C', s.species());
    }

    @Test
    void withChangeCode3_movesToCompositeDefender() {
        BotState s = BotState.initial('M').withChangeCode('3');
        assertEquals(BotState.Embodiment.COMPOSITE_MEMBER, s.embodiment());
        assertEquals(Integer.valueOf(3), s.compositeRole());
        assertFalse(s.hasFullAuthority(), "DEFENDER (role 3) is passive");
    }

    @Test
    void withChangeCode0_movesToCompositeLocomotor() {
        BotState s = BotState.initial('C').withChangeCode('0');
        assertEquals(BotState.Embodiment.COMPOSITE_MEMBER, s.embodiment());
        assertEquals(Integer.valueOf(0), s.compositeRole());
        assertTrue(s.hasFullAuthority(), "LOCOMOTOR (role 0) has full authority");
    }

    @Test
    void invalidSpeciesRejected() {
        assertThrows(IllegalArgumentException.class, () -> new BotState('X', BotState.Embodiment.SOLO, null));
        assertThrows(IllegalArgumentException.class, () -> BotState.initial('X'));
    }

    @Test
    void compositeMemberRequiresRole() {
        assertThrows(IllegalArgumentException.class,
                () -> new BotState('C', BotState.Embodiment.COMPOSITE_MEMBER, null));
        assertThrows(IllegalArgumentException.class,
                () -> new BotState('C', BotState.Embodiment.COMPOSITE_MEMBER, 6));
        assertThrows(IllegalArgumentException.class,
                () -> new BotState('C', BotState.Embodiment.COMPOSITE_MEMBER, -1));
    }

    @Test
    void nonCompositeMustHaveNullRole() {
        assertThrows(IllegalArgumentException.class,
                () -> new BotState('C', BotState.Embodiment.SOLO, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new BotState('C', BotState.Embodiment.BONDED_PRIMARY, 1));
    }

    @Test
    void unknownChangeCodeRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> BotState.initial('C').withChangeCode('X'));
        assertThrows(IllegalArgumentException.class,
                () -> BotState.initial('C').withChangeCode('9'));
    }
}
