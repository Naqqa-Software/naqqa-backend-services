package com.naqqa.elasticsearch.monitor.shutdown;

import com.naqqa.elasticsearch.test.Test;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static com.naqqa.elasticsearch.test.Assert.assertEquals;
import static com.naqqa.elasticsearch.test.Assert.assertFalse;
import static com.naqqa.elasticsearch.test.Assert.assertTrue;

public final class ShutdownCoordinatorTest {

    @Test
    public void phasesRunInOrderAndDrainSucceedsWithoutForceClose() {
        FakeClock clock = new FakeClock(Instant.EPOCH);
        List<String> events = new ArrayList<>();
        AtomicInteger inFlight = new AtomicInteger(3);

        List<ShutdownCoordinator.ShutdownHook> hooks = List.of(hook("hook-1", () -> events.add("hook-1")),
                hook("hook-2", () -> events.add("hook-2")));

        ShutdownCoordinator coordinator = new ShutdownCoordinator(clock, () -> events.add("stop-accepting"),
                inFlight::get, hooks, 1000, () -> {
                    inFlight.decrementAndGet();
                    clock.advance(100);
                });

        List<ShutdownCoordinator.PhaseResult> results = coordinator.shutdown();

        assertEquals(3, results.size());
        assertEquals(ShutdownCoordinator.Phase.STOP_ACCEPTING_REQUESTS, results.get(0).phase());
        assertEquals(ShutdownCoordinator.Phase.DRAIN_IN_FLIGHT, results.get(1).phase());
        assertEquals(ShutdownCoordinator.Phase.RUN_SHUTDOWN_HOOKS, results.get(2).phase());
        assertTrue(results.get(1).success());
        assertEquals(List.of("stop-accepting", "hook-1", "hook-2"), events);
        assertFalse(coordinator.isForceClosed());
        assertFalse(coordinator.isAcceptingRequests());
    }

    @Test
    public void forcesCloseWhenDrainTimesOut() {
        FakeClock clock = new FakeClock(Instant.EPOCH);
        AtomicInteger inFlight = new AtomicInteger(5);

        ShutdownCoordinator coordinator = new ShutdownCoordinator(clock, () -> {
        }, inFlight::get, List.of(), 500, () -> clock.advance(200));

        List<ShutdownCoordinator.PhaseResult> results = coordinator.shutdown();

        assertEquals(4, results.size());
        assertEquals(ShutdownCoordinator.Phase.FORCE_CLOSE, results.get(3).phase());
        assertFalse(results.get(1).success());
        assertTrue(coordinator.isForceClosed());
    }

    private static ShutdownCoordinator.ShutdownHook hook(String name, Runnable action) {
        return new ShutdownCoordinator.ShutdownHook() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public void run() {
                action.run();
            }
        };
    }
}
