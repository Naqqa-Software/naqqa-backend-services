package com.naqqa.elasticsearch.common.time;

import java.time.Instant;
import java.time.ZoneId;

final class MultiDateFormatter implements DateFormatter {

    private final String pattern;
    private final DateFormatter[] formatters;

    MultiDateFormatter(String pattern, DateFormatter[] formatters) {
        this.pattern = pattern;
        this.formatters = formatters;
    }

    @Override
    public Instant parse(String input) {
        RuntimeException last = null;
        for (DateFormatter f : formatters) {
            try {
                return f.parse(input);
            } catch (RuntimeException e) {
                last = e;
            }
        }
        throw new IllegalArgumentException("Failed to parse date [" + input + "] with formats [" + pattern + "]", last);
    }

    @Override
    public String format(Instant instant) {
        return formatters[0].format(instant);
    }

    @Override
    public DateFormatter withZone(ZoneId zone) {
        DateFormatter[] copy = new DateFormatter[formatters.length];
        for (int i = 0; i < formatters.length; i++) {
            copy[i] = formatters[i].withZone(zone);
        }
        return new MultiDateFormatter(pattern, copy);
    }

    @Override
    public String pattern() {
        return pattern;
    }

    @Override
    public ZoneId zone() {
        return formatters[0].zone();
    }
}
