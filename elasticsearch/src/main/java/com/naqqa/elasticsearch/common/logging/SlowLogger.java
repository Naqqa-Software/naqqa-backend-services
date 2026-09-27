package com.naqqa.elasticsearch.common.logging;

import com.naqqa.elasticsearch.common.unit.TimeValue;

public final class SlowLogger {

    private final ESLogger logger;
    private volatile long warnThresholdNanos = -1;
    private volatile long infoThresholdNanos = -1;
    private volatile long debugThresholdNanos = -1;
    private volatile long traceThresholdNanos = -1;

    public SlowLogger(String name) {
        this.logger = ESLogger.getLogger(name);
    }

    public void setWarnThreshold(TimeValue value) {
        warnThresholdNanos = value == null ? -1 : value.nanos();
    }

    public void setInfoThreshold(TimeValue value) {
        infoThresholdNanos = value == null ? -1 : value.nanos();
    }

    public void setDebugThreshold(TimeValue value) {
        debugThresholdNanos = value == null ? -1 : value.nanos();
    }

    public void setTraceThreshold(TimeValue value) {
        traceThresholdNanos = value == null ? -1 : value.nanos();
    }

    public void maybeLog(String message, long tookNanos) {
        long tookMillis = tookNanos / 1_000_000;
        if (warnThresholdNanos >= 0 && tookNanos >= warnThresholdNanos) {
            logger.warn("took[{}ms]: {}", tookMillis, message);
        } else if (infoThresholdNanos >= 0 && tookNanos >= infoThresholdNanos) {
            logger.info("took[{}ms]: {}", tookMillis, message);
        } else if (debugThresholdNanos >= 0 && tookNanos >= debugThresholdNanos) {
            logger.debug("took[{}ms]: {}", tookMillis, message);
        } else if (traceThresholdNanos >= 0 && tookNanos >= traceThresholdNanos) {
            logger.trace("took[{}ms]: {}", tookMillis, message);
        }
    }
}
