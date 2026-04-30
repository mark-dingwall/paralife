package com.paralife.bot;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that {@link BotRunner} launches bots with {@link BotIdentity#operator()} identity.
 *
 * <p><b>Round B M-05 rewrite.</b> The previous version of this test computed an
 * {@code ops} count over {@link com.paralife.websocket.SessionRegistry} and asserted
 * {@code rc==0} (which was already asserted outside the Awaitility block) — making the
 * Awaitility body tautological and the test pass for the wrong reason. It also depended
 * on a live Spring context and was flaky against duration timing.
 *
 * <p>The current version injects a recording {@link BotFleet} double via the existing
 * {@code fleetFactory} seam, captures the {@link BotIdentity} argument passed to
 * {@link BotFleet#launch}, and asserts identity equality directly. No Spring context
 * required; deterministic and runs in milliseconds.
 */
class BotRunnerOperatorTagTest {

    @Test
    void botRunnerLaunchesWithOperatorIdentity() {
        AtomicReference<BotIdentity> capturedIdentity = new AtomicReference<>();

        // Recording double: subclass of BotFleet that captures the identity argument and
        // returns an empty bot list so BotRunner's awaitAllSettled() completes immediately.
        Supplier<BotFleet> recordingFactory = () -> new BotFleet() {
            @Override
            public List<BotClient> launch(String uri, int count, BotIdentity identity,
                                          RampUpSpec rampUp, SpeciesMix mix, BotFactory factory) {
                capturedIdentity.set(identity);
                return List.of();
            }
        };

        Function<String, BotFactory> factoryFactory = BotFactory::new;

        // count=1, duration=1s — duration sleep dominates wall clock; otherwise the test
        // is purely arg-passing inspection.
        int rc = BotRunner.run(
                new String[]{"ws://localhost:9999/ws/world", "1", "1"},
                recordingFactory, factoryFactory);

        assertThat(rc).as("clean shutdown").isEqualTo(0);
        assertThat(capturedIdentity.get())
                .as("BotRunner must launch with BotIdentity.operator()")
                .isEqualTo(BotIdentity.operator());
    }
}
