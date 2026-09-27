package com.naqqa.elasticsearch.common.time;

import java.time.Instant;
import java.time.ZoneId;

final class EpochMillisDateFormatter implements DateFormatter {

    private final ZoneId zone;

    EpochMillisDateFormatter(ZoneId zone) {
        this.zone = zone;
    }

    @Override
    public Instant parse(String input) {
        int dot = input.indexOf('.');
        if (dot < 0) {
            return Instant.ofEpochMilli(Long.parseLong(input));
        }
        long millis = Long.parseLong(input.substring(0, dot));
        String fraction = input.substring(dot + 1);
        long nanos = Long.parseLong((fraction + "000000").substring(0, 6)) * 1000;
        long sign = millis < 0 ? -1 : 1;
        return Instant.ofEpochMilli(millis).plusNanos(sign * nanos);
    }

    @Override
    public String format(Instant instant) {
        return String.valueOf(instant.toEpochMilli());
    }

    @Override
    public DateFormatter withZone(ZoneId zone) {
        return new EpochMillisDateFormatter(zone);
    }

    @Override
    public String pattern() {
        return "epoch_millis";
    }

    @Override
    public ZoneId zone() {
        return zone;
    }
}
