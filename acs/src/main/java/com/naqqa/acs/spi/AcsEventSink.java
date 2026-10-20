package com.naqqa.acs.spi;

import java.util.Map;

@FunctionalInterface
public interface AcsEventSink {

    void report(Map<String, Object> event);
}
