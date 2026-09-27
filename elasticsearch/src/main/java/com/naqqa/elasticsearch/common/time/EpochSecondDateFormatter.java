package com.naqqa.elasticsearch.common.time;

import java.time.Instant;
import java.time.ZoneId;

final class EpochSecondDateFormatter implements DateFormatter {

    private final ZoneId zone;

    EpochSecondDateFormatter(ZoneId zone) {
        this.zone = zone;
    }

    @Override
    public Instant parse(String input) {
        int dot = input.indexOf('.');
        if (dot < 0) {
            return Instant.ofEpochSecond(Long.parseLong(input));
        }
        long seconds = Long.parseLong(input.substring(0, dot));
        String fraction = input.substring(dot + 1);
        long nanos = Long.parseLong((fraction + "000000000").substring(0, 9));
        return Instant.ofEpochSecond(seconds, nanos);
    }

    @Override
    public String format(Instant instant) {
        return String.valueOf(instant.getEpochSecond());
    }

    @Override
    public DateFormatter withZone(ZoneId zone) {
        return new EpochSecondDateFormatter(zone);
    }

    @Override
    public String pattern() {
        return "epoch_second";
    }

    @Override
    public ZoneId zone() {
        return zone;
    }
}
