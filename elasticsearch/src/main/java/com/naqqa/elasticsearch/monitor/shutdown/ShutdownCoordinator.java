package com.naqqa.elasticsearch.monitor.shutdown;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntSupplier;

public final class ShutdownCoordinator {

    public enum Phase {
        STOP_ACCEPTING_REQUESTS,
        DRAIN_IN_FLIGHT,
        RUN_SHUTDOWN_HOOKS,
        FORCE_CLOSE
    }

    public record PhaseResult(Phase phase, long startMillis, long endMillis, boolean success, String detail) {
    }

    public interface ShutdownHook {

        String name();

        void run();
    }

    private final Clock clock;
    private final Runnable stopAcceptingRequests;
    private final IntSupplier inFlightCount;
    private final List<ShutdownHook> hooks;
    private final long drainTimeoutMillis;
    private final Runnable pollStep;

    private volatile boolean acceptingRequests = true;
    private volatile boolean forceClosed = false;

    public ShutdownCoordinator(Clock clock, Runnable stopAcceptingRequests, IntSupplier inFlightCount,
            List<ShutdownHook> hooks, long drainTimeoutMillis, Runnable pollStep) {
        this.clock = clock;
        this.stopAcceptingRequests = stopAcceptingRequests;
        this.inFlightCount = inFlightCount;
        this.hooks = hooks;
        this.drainTimeoutMillis = drainTimeoutMillis;
        this.pollStep = pollStep;
    }

    public boolean isAcceptingRequests() {
        return acceptingRequests;
    }

    public boolean isForceClosed() {
        return forceClosed;
    }

    public List<PhaseResult> shutdown() {
        List<PhaseResult> results = new ArrayList<>();
        results.add(stopAcceptingPhase());
        results.add(drainPhase());
        results.add(hooksPhase());
        if (inFlightCount.getAsInt() > 0) {
            results.add(forceClosePhase());
        }
        return results;
    }

    private PhaseResult stopAcceptingPhase() {
        long start = clock.millis();
        acceptingRequests = false;
        stopAcceptingRequests.run();
        long end = clock.millis();
        return new PhaseResult(Phase.STOP_ACCEPTING_REQUESTS, start, end, true, "stopped accepting new requests");
    }

    private PhaseResult drainPhase() {
        long start = clock.millis();
        while (inFlightCount.getAsInt() > 0 && clock.millis() - start < drainTimeoutMillis) {
            pollStep.run();
        }
        long end = clock.millis();
        boolean drained = inFlightCount.getAsInt() == 0;
        String detail = drained ? "all in-flight requests completed"
                : inFlightCount.getAsInt() + " in-flight request(s) remaining after drain timeout";
        return new PhaseResult(Phase.DRAIN_IN_FLIGHT, start, end, drained, detail);
    }

    private PhaseResult hooksPhase() {
        long start = clock.millis();
        List<String> failed = new ArrayList<>();
        for (ShutdownHook hook : hooks) {
            try {
                hook.run();
            } catch (RuntimeException e) {
                failed.add(hook.name() + ": " + e.getMessage());
            }
        }
        long end = clock.millis();
        boolean success = failed.isEmpty();
        String detail = success ? "ran " + hooks.size() + " shutdown hook(s)" : "hook failures: " + failed;
        return new PhaseResult(Phase.RUN_SHUTDOWN_HOOKS, start, end, success, detail);
    }

    private PhaseResult forceClosePhase() {
        long start = clock.millis();
        forceClosed = true;
        int remaining = inFlightCount.getAsInt();
        long end = clock.millis();
        return new PhaseResult(Phase.FORCE_CLOSE, start, end, true,
                "force closed " + remaining + " remaining in-flight request(s)");
    }
}
