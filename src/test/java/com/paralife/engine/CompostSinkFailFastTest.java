package com.paralife.engine;

import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import org.springframework.context.event.ContextRefreshedEvent;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * cycle-9 action A: locks the CompostSink fail-fast contract.
 *
 * <p>If {@link EnvCleanupHooksBean}'s
 * {@code ApplicationListener<ContextRefreshedEvent>} is silently dropped by a
 * future refactor, the production DI graph would continue to work — because
 * {@link EnvironmentEngine} registers itself as the sink in {@code @PostConstruct}.
 * But ANY startup ordering regression would cause silent compost-event loss.
 *
 * <p>Rather than bootstrapping a minimal Spring context that excludes
 * {@link EnvironmentEngine} (awkward — @SpringBootApplication scans the entire
 * com.paralife package), this test instantiates
 * {@link EnvCleanupHooksBean} directly and fires a synthetic
 * {@link ContextRefreshedEvent}. The fail-fast check throws loudly because no
 * sink was ever registered.
 *
 * <p>If a future refactor removes the {@code ApplicationListener} implementation
 * or the {@code onApplicationEvent} throws, this test will either stop
 * compiling or fail — either way the regression is caught.
 */
class CompostSinkFailFastTest {

    @Test
    void compostSinkMissingFailsFastAtStartup() {
        // Build a bean with NO sink registered.
        EnvCleanupHooksBean bean = new EnvCleanupHooksBean();

        // Fire a synthetic ContextRefreshedEvent — the same event Spring fires
        // after context refresh completes. The bean's onApplicationEvent must
        // throw IllegalStateException because compostSink is still null.
        ApplicationContext mockCtx = mock(ApplicationContext.class);
        ContextRefreshedEvent event = new ContextRefreshedEvent(mockCtx);

        assertThatThrownBy(() -> bean.onApplicationEvent(event))
                .as("cycle-9 action A — fail-fast when CompostSink never registered")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CompostSink was never registered");
    }
}
