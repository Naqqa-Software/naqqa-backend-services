package com.naqqa.elasticsearch.common.cron;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class IntervalSchedule {

    private static final Pattern PATTERN = Pattern.compile("^(\\d+)\\s*(ms|s|m|h|d|w)$");

    private final Duration interval;
    private final String source;

    private IntervalSchedule(Duration interval, String source) {
        if (interval.isZero() || interval.isNegative()) {
            throw new IllegalArgumentException("interval must be positive: " + source);
        }
        this.interval = interval;
        this.source = source;
    }

    public static IntervalSchedule parse(String value) {
        String trimmed = value.trim();
        Matcher m = PATTERN.matcher(trimmed);
        if (!m.matches()) {
            throw new IllegalArgumentException("invalid interval expression [" + value + "], expected e.g. '1h', '30m', '10s'");
        }
        long amount = Long.parseLong(m.group(1));
        String unit = m.group(2);
        Duration d = switch (unit) {
            case "ms" -> Duration.ofMillis(amount);
            case "s" -> Duration.ofSeconds(amount);
            case "m" -> Duration.ofMinutes(amount);
            case "h" -> Duration.ofHours(amount);
            case "d" -> Duration.ofDays(amount);
            case "w" -> Duration.ofDays(amount * 7);
            default -> throw new IllegalArgumentException("unknown interval unit [" + unit + "]");
        };
        return new IntervalSchedule(d, trimmed);
    }

    public Duration duration() {
        return interval;
    }

    public ZonedDateTime nextValidTimeAfter(ZonedDateTime after) {
        return after.plus(interval);
    }

    public String getSource() {
        return source;
    }

    @Override
    public String toString() {
        return source;
    }
}
