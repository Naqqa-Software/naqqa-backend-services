package com.naqqa.elasticsearch.monitor.slowlog;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;

public final class SlowLogService {

    private static final int MAX_SOURCE_EXCERPT = 1000;

    private final SlowLogSink sink;
    private volatile SlowLogThresholds queryThresholds;
    private volatile SlowLogThresholds fetchThresholds;
    private volatile SlowLogThresholds indexingThresholds;

    public SlowLogService(SlowLogSink sink) {
        this.sink = sink;
        this.queryThresholds = new SlowLogThresholds();
        this.fetchThresholds = new SlowLogThresholds();
        this.indexingThresholds = new SlowLogThresholds();
    }

    public void setQueryThresholds(SlowLogThresholds thresholds) {
        this.queryThresholds = thresholds;
    }

    public void setFetchThresholds(SlowLogThresholds thresholds) {
        this.fetchThresholds = thresholds;
    }

    public void setIndexingThresholds(SlowLogThresholds thresholds) {
        this.indexingThresholds = thresholds;
    }

    public void onQueryPhase(String index, int shard, long tookMillis, String source) {
        log(queryThresholds, "index.search.slowlog.query", index, shard, tookMillis, source);
    }

    public void onFetchPhase(String index, int shard, long tookMillis, String source) {
        log(fetchThresholds, "index.search.slowlog.fetch", index, shard, tookMillis, source);
    }

    public void onIndexing(String index, int shard, long tookMillis, String source) {
        log(indexingThresholds, "index.indexing.slowlog", index, shard, tookMillis, source);
    }

    private void log(SlowLogThresholds thresholds, String category, String index, int shard, long tookMillis,
            String source) {
        SlowLogLevel level = thresholds.matchLevel(tookMillis);
        if (level == null) {
            return;
        }
        sink.write(level, category, formatLine(level, category, index, shard, tookMillis, source));
    }

    private String formatLine(SlowLogLevel level, String category, String index, int shard, long tookMillis,
            String source) {
        String excerpt = excerpt(source);
        String timestamp = DateTimeFormatter.ISO_INSTANT.format(Instant.now());
        return "[" + timestamp + "][" + level + "][" + category + "] took[" + tookMillis + "ms], took_millis["
                + tookMillis + "], index[" + index + "], shard[" + shard + "], source[" + excerpt + "]";
    }

    private String excerpt(String source) {
        if (source == null) {
            return "";
        }
        if (source.length() <= MAX_SOURCE_EXCERPT) {
            return source;
        }
        return source.substring(0, MAX_SOURCE_EXCERPT) + "...";
    }

    public static SlowLogThresholds thresholds(long warnMillis, long infoMillis, long debugMillis, long traceMillis) {
        return new SlowLogThresholds().set(SlowLogLevel.WARN, warnMillis).set(SlowLogLevel.INFO, infoMillis)
                .set(SlowLogLevel.DEBUG, debugMillis).set(SlowLogLevel.TRACE, traceMillis);
    }

    public static List<SlowLogLevel> levelsBySeverity() {
        return List.of(SlowLogLevel.WARN, SlowLogLevel.INFO, SlowLogLevel.DEBUG, SlowLogLevel.TRACE);
    }
}
