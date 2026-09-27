package com.naqqa.elasticsearch.search.advanced.async;

import com.naqqa.elasticsearch.test.Test;

import static com.naqqa.elasticsearch.test.Assert.assertEquals;
import static com.naqqa.elasticsearch.test.Assert.assertFalse;
import static com.naqqa.elasticsearch.test.Assert.assertNotNull;
import static com.naqqa.elasticsearch.test.Assert.assertTrue;

public final class AsyncSearchServiceTest {

    @Test
    public void fastSearchCompletesWithoutBeingReportedAsRunning() throws Exception {
        try (AsyncSearchService svc = new AsyncSearchService()) {
            AsyncSearchService.Response<String> resp = svc.submit(() -> "done", 2000L, 60_000L);
            assertFalse(resp.isRunning());
            assertFalse(resp.isPartial());
            assertEquals("done", resp.result());
        }
    }

    @Test
    public void slowSearchReturnsIdImmediatelyThenCompletesOnLaterPoll() throws Exception {
        try (AsyncSearchService svc = new AsyncSearchService()) {
            AsyncSearchService.Response<String> resp = svc.submit(() -> {
                Thread.sleep(400);
                return "slow-done";
            }, 50L, 60_000L);
            assertTrue(resp.isRunning());
            assertTrue(resp.isPartial());
            assertNotNull(resp.id());

            AsyncSearchService.Response<String> polled = null;
            long deadline = System.nanoTime() + 5_000_000_000L;
            while (System.nanoTime() < deadline) {
                polled = svc.poll(resp.id());
                if (!polled.isRunning()) {
                    break;
                }
                Thread.sleep(30);
            }
            assertNotNull(polled);
            assertFalse(polled.isRunning());
            assertEquals("slow-done", polled.result());
        }
    }

    @Test
    public void deleteRemovesContextAndIsIdempotentFalseOnSecondCall() throws Exception {
        try (AsyncSearchService svc = new AsyncSearchService()) {
            AsyncSearchService.Response<String> resp = svc.submit(() -> "x", 2000L, 60_000L);
            assertTrue(svc.delete(resp.id()));
            assertFalse(svc.delete(resp.id()));
        }
    }
}
