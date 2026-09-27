package com.naqqa.elasticsearch.monitor.slowlog;

public interface SlowLogSink {

    void write(SlowLogLevel level, String category, String line);
}
