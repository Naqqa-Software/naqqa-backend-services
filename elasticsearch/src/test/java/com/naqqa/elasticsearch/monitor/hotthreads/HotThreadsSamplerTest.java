package com.naqqa.elasticsearch.monitor.hotthreads;

import com.naqqa.elasticsearch.test.Test;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.naqqa.elasticsearch.test.Assert.assertTrue;

public final class HotThreadsSamplerTest {

    @Test
    public void ranksBusyThreadAboveIdleThread() throws InterruptedException {
        AtomicBoolean keepBusy = new AtomicBoolean(true);
        AtomicBoolean keepIdle = new AtomicBoolean(true);

        Thread busyThread = new Thread(() -> {
            long counter = 0;
            while (keepBusy.get()) {
                counter += Math.abs(System.nanoTime() % 7);
            }
            if (counter < 0) {
                throw new IllegalStateException();
            }
        }, "busy-worker-test");
        busyThread.setDaemon(true);

        Thread idleThread = new Thread(() -> {
            while (keepIdle.get()) {
                try {
                    Thread.sleep(20);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }, "idle-worker-test");
        idleThread.setDaemon(true);

        busyThread.start();
        idleThread.start();
        try {
            Thread.sleep(50);
            HotThreadsSampler sampler = new HotThreadsSampler();
            List<HotThreadsSampler.HotThread> results = sampler.sample(3, 300, 10);

            HotThreadsSampler.HotThread busyResult = find(results, "busy-worker-test");
            HotThreadsSampler.HotThread idleResult = find(results, "idle-worker-test");

            assertTrue(busyResult != null, "expected busy thread in results");
            assertTrue(idleResult != null, "expected idle thread in results");
            assertTrue(busyResult.percentBusy() > idleResult.percentBusy(),
                    "busy thread should be busier than idle thread: " + busyResult.percentBusy() + " vs "
                            + idleResult.percentBusy());
            assertTrue(busyResult.percentBusy() > 20.0, "busy thread should be substantially busy: "
                    + busyResult.percentBusy());

            String rendered = sampler.render(results, 5, 300, 3);
            assertTrue(rendered.contains("busy-worker-test"));
        } finally {
            keepBusy.set(false);
            keepIdle.set(false);
            busyThread.join(1000);
            idleThread.join(1000);
        }
    }

    private static HotThreadsSampler.HotThread find(List<HotThreadsSampler.HotThread> results, String name) {
        for (HotThreadsSampler.HotThread thread : results) {
            if (thread.threadName().equals(name)) {
                return thread;
            }
        }
        return null;
    }
}
