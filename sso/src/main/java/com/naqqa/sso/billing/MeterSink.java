package com.naqqa.sso.billing;

@FunctionalInterface
public interface MeterSink {

    void accept(UsageEvent event);
}
