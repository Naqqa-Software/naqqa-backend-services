package com.naqqa.elasticsearch.search.advanced.pagination;

import com.naqqa.elasticsearch.index.engine.EngineSearcher;
import com.naqqa.elasticsearch.test.Test;

import java.util.List;

import static com.naqqa.elasticsearch.test.Assert.assertFalse;
import static com.naqqa.elasticsearch.test.Assert.assertNotNull;
import static com.naqqa.elasticsearch.test.Assert.assertThrows;
import static com.naqqa.elasticsearch.test.Assert.assertTrue;

public final class PitServiceTest {

    @Test
    public void keepAliveExtendsExpiryPastOriginalTtl() throws Exception {
        try (PitService pitService = new PitService(30L)) {
            EngineSearcher engineSearcher = new EngineSearcher(List.of(), () -> { });
            String id = pitService.openFrom(engineSearcher, 150L);
            Thread.sleep(80);
            pitService.keepAlive(id, 500L);
            Thread.sleep(150);
            assertNotNull(pitService.get(id));
            assertTrue(pitService.close(id));
            assertFalse(pitService.close(id));
        }
    }

    @Test
    public void reaperClosesExpiredContexts() throws Exception {
        try (PitService pitService = new PitService(30L)) {
            EngineSearcher engineSearcher = new EngineSearcher(List.of(), () -> { });
            String id = pitService.openFrom(engineSearcher, 60L);

            long deadline = System.nanoTime() + 3_000_000_000L;
            boolean reaped = false;
            while (System.nanoTime() < deadline) {
                if (pitService.size() == 0) {
                    reaped = true;
                    break;
                }
                Thread.sleep(20);
            }
            assertTrue(reaped);
            assertThrows(IllegalArgumentException.class, () -> pitService.get(id));
        }
    }
}
