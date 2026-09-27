package com.naqqa.elasticsearch.monitor.slowlog;

import com.naqqa.elasticsearch.test.Test;
import java.util.List;

import static com.naqqa.elasticsearch.test.Assert.assertEquals;
import static com.naqqa.elasticsearch.test.Assert.assertTrue;

public final class SlowLogServiceTest {

    @Test
    public void triggersAtCorrectLevelBasedOnThreshold() {
        InMemorySlowLogSink sink = new InMemorySlowLogSink();
        SlowLogService service = new SlowLogService(sink);
        service.setQueryThresholds(SlowLogService.thresholds(1000, 500, 100, 10));

        service.onQueryPhase("my-index", 0, 5, "{\"match\":{}}");
        assertEquals(0, sink.entries().size());

        service.onQueryPhase("my-index", 0, 50, "{\"match\":{}}");
        assertEquals(1, sink.entries().size());
        assertEquals(SlowLogLevel.TRACE, sink.entries().get(0).level());

        service.onQueryPhase("my-index", 0, 150, "{\"match\":{}}");
        assertEquals(2, sink.entries().size());
        assertEquals(SlowLogLevel.DEBUG, sink.entries().get(1).level());

        service.onQueryPhase("my-index", 0, 750, "{\"match\":{}}");
        assertEquals(3, sink.entries().size());
        assertEquals(SlowLogLevel.INFO, sink.entries().get(2).level());

        service.onQueryPhase("my-index", 1, 5000, "{\"match\":{}}");
        assertEquals(4, sink.entries().size());
        InMemorySlowLogSink.Entry warnEntry = sink.entries().get(3);
        assertEquals(SlowLogLevel.WARN, warnEntry.level());
        assertTrue(warnEntry.line().contains("took[5000ms]"));
        assertTrue(warnEntry.line().contains("index[my-index]"));
        assertTrue(warnEntry.line().contains("shard[1]"));
    }

    @Test
    public void indexingSlowLogUsesSeparateThresholds() {
        InMemorySlowLogSink sink = new InMemorySlowLogSink();
        SlowLogService service = new SlowLogService(sink);
        service.setIndexingThresholds(SlowLogService.thresholds(200, -1, -1, -1));

        service.onIndexing("my-index", 0, 50, "{\"field\":\"value\"}");
        assertEquals(0, sink.entries().size());

        service.onIndexing("my-index", 0, 250, "{\"field\":\"value\"}");
        assertEquals(1, sink.entries().size());
        assertEquals(SlowLogLevel.WARN, sink.entries().get(0).level());
        List<InMemorySlowLogSink.Entry> entries = sink.entries();
        assertTrue(entries.get(0).category().contains("indexing"));
    }
}
