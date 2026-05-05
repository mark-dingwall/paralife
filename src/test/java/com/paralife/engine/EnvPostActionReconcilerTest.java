package com.paralife.engine;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.core.annotation.Order;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for {@link EnvPostActionReconciler} — cycle-4 action item #2.
 *
 * <p>Asserts:
 * <ol>
 *   <li>@Order(25) present on the onTick listener — strictly between
 *       ActionResolver(@Order 20) and TickBroadcaster(@Order 50).</li>
 *   <li>onTick invokes {@link EnvironmentEngine#processEnvDeaths()} first, then
 *       {@link EnvironmentEngine#drainPostActionGrants()}.</li>
 * </ol>
 *
 * <p>Plan 14-03 Task 2 Step 5 UPDATES this test to verify
 * {@code drainPostActionGrants(anyLong())} once the signature changes.
 * Plan 01 ships the no-arg verify (cycle-6 HIGH #5b).
 */
class EnvPostActionReconcilerTest {

    @Test
    void tickOrderIs25BetweenActionResolverAndTickBroadcaster() throws Exception {
        Method onTick = EnvPostActionReconciler.class.getMethod("onTick", TickEvent.class);
        Order order = onTick.getAnnotation(Order.class);
        assertThat(order).as("@Order present on onTick").isNotNull();
        assertThat(order.value())
                .as("@Order(25) — strictly between ActionResolver(@Order 20) and TickBroadcaster(@Order 50)")
                .isEqualTo(25);
        assertThat(EnvPostActionReconciler.TICK_ORDER).isEqualTo(25);
    }

    @Test
    void onTickCallsProcessEnvDeathsThenDrainPostActionGrants() {
        EnvironmentEngine env = mock(EnvironmentEngine.class);
        EnvPostActionReconciler reconciler = new EnvPostActionReconciler(env);

        reconciler.onTick(new TickEvent(42L));

        InOrder inOrder = inOrder(env);
        inOrder.verify(env).processEnvDeaths();
        inOrder.verify(env).drainPostActionGrants(anyLong());
        inOrder.verifyNoMoreInteractions();
    }
}
