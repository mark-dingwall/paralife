package com.paralife.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.paralife.world.Position;
import org.junit.jupiter.api.Test;

class AlarmQueueTest {

    private final AlarmQueue alarms = new AlarmQueue();

    @Test
    void drainReturnsExactEntriesInFifoOrderForOnlyRequestedComposite() {
        alarms.enqueueAlarm("composite-a", new Position(2, 3), 11L);
        alarms.enqueueAlarm("composite-b", new Position(7, 5), 12L);
        alarms.enqueueAlarm("composite-a", new Position(4, 6), 13L);

        assertThat(alarms.drainAlarms("composite-a"))
                .containsExactly(
                        new AlarmQueue.AlarmEntry("composite-a", new Position(2, 3), 11L),
                        new AlarmQueue.AlarmEntry("composite-a", new Position(4, 6), 13L));
        assertThat(alarms.drainAlarms("composite-b"))
                .containsExactly(
                        new AlarmQueue.AlarmEntry("composite-b", new Position(7, 5), 12L));
    }

    @Test
    void drainDeliversPendingAlarmsAtMostOnce() {
        alarms.enqueueAlarm("composite-a", new Position(1, 1), 21L);

        assertThat(alarms.drainAlarms("composite-a")).hasSize(1);
        assertThat(alarms.drainAlarms("composite-a")).isEmpty();
    }

    @Test
    void drainOfUnknownCompositeIsEmpty() {
        assertThat(alarms.drainAlarms("missing-composite")).isEmpty();
    }

    @Test
    void nullCompositeEnqueueIsNoOpWhileValidCompositeStillEnqueues() {
        alarms.enqueueAlarm(null, new Position(8, 8), 30L);
        alarms.enqueueAlarm("composite-a", new Position(3, 4), 31L);

        assertThat(alarms.drainAlarms("composite-a"))
                .containsExactly(
                        new AlarmQueue.AlarmEntry("composite-a", new Position(3, 4), 31L));
    }
}
