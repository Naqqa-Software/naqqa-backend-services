package com.naqqa.elasticsearch.common.time;

import java.time.Instant;
import java.time.ZoneId;

public interface DateFormatter {

    Instant parse(String input);

    String format(Instant instant);

    DateFormatter withZone(ZoneId zone);

    String pattern();

    ZoneId zone();

    default long parseMillis(String input) {
        return parse(input).toEpochMilli();
    }

    default long parseNanos(String input) {
        Instant instant = parse(input);
        return instant.getEpochSecond() * 1_000_000_000L + instant.getNano();
    }

    default String formatMillis(long millis) {
        return format(Instant.ofEpochMilli(millis));
    }

    default String formatNanos(long nanos) {
        return format(Instant.ofEpochSecond(nanos / 1_000_000_000L, nanos % 1_000_000_000L));
    }

    static DateFormatter forPattern(String input) {
        if (input == null || input.isEmpty()) {
            throw new IllegalArgumentException("No date pattern provided");
        }
        if (input.contains("||")) {
            String[] parts = input.split("\\|\\|");
            DateFormatter[] formatters = new DateFormatter[parts.length];
            for (int i = 0; i < parts.length; i++) {
                formatters[i] = forSinglePattern(parts[i]);
            }
            return new MultiDateFormatter(input, formatters);
        }
        return forSinglePattern(input);
    }

    private static DateFormatter forSinglePattern(String pattern) {
        DateFormatter named = DateFormatters.byName(pattern);
        if (named != null) {
            return named;
        }
        return new JavaDateFormatter(pattern, ZoneId.of("UTC"));
    }
}
