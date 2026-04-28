package com.paralife.bot;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link BotFactory} — the bot-construction chokepoint (Phase 18 D-19).
 */
class BotFactoryTest {

    private static final String URI = "ws://localhost:8080/ws/world";

    @Test
    void create_withOperatorIdentity_returnsBotClientWithCorrectSpecies() {
        BotFactory factory = new BotFactory(URI);
        BotClient bot = factory.create('C', BotIdentity.operator(), Optional.empty(), Optional.empty());

        assertThat(bot).isNotNull();
        assertThat(bot.identity()).isEqualTo(BotIdentity.operator());
    }

    @Test
    void create_withHarnessIdentity_returnsClientWithHarnessIdentity() {
        BotFactory factory = new BotFactory(URI);
        BotClient bot = factory.create('C', BotIdentity.harness("h1"), Optional.empty(), Optional.empty());

        assertThat(bot).isNotNull();
        assertThat(bot.identity()).isEqualTo(BotIdentity.harness("h1"));
        assertThat(bot.identity().harnessId()).contains("h1");
    }

    @Test
    void create_withInvalidSpecies_throwsIllegalArgumentException() {
        BotFactory factory = new BotFactory(URI);
        assertThatThrownBy(() -> factory.create('X', BotIdentity.operator(), Optional.empty(), Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void create_claimEntityIdAndToken_areNoOpsToday() {
        // D-19: claimEntityId + claimToken are reserved for backlog 999.2 and currently no-ops.
        BotFactory factory = new BotFactory(URI);
        BotClient bot = factory.create('M', BotIdentity.operator(),
                Optional.of("some-entity-id"), Optional.of("some-token"));

        assertThat(bot).isNotNull();
        assertThat(bot.identity()).isEqualTo(BotIdentity.operator());
    }

    @Test
    void create_allThreeSpecies_returnValidClients() {
        BotFactory factory = new BotFactory(URI);
        for (char sp : new char[]{'C', 'M', 'S'}) {
            BotClient bot = factory.create(sp, BotIdentity.operator(), Optional.empty(), Optional.empty());
            assertThat(bot).isNotNull();
        }
    }
}
