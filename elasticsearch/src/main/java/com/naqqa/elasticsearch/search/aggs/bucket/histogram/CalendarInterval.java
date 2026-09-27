package com.naqqa.elasticsearch.search.aggs.bucket.histogram;

import java.time.DayOfWeek;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Locale;

public enum CalendarInterval {
    SECOND,
    MINUTE,
    HOUR,
    DAY,
    WEEK,
    MONTH,
    QUARTER,
    YEAR;

    public static CalendarInterval fromString(String s) {
        return switch (s.toLowerCase(Locale.ROOT)) {
            case "second", "1s" -> SECOND;
            case "minute", "1m" -> MINUTE;
            case "hour", "1h" -> HOUR;
            case "day", "1d" -> DAY;
            case "week", "1w" -> WEEK;
            case "month", "1M" -> MONTH;
            case "quarter", "1q" -> QUARTER;
            case "year", "1y" -> YEAR;
            default -> throw new IllegalArgumentException("unknown calendar_interval [" + s + "]");
        };
    }

    public long truncateToMillis(long epochMillis, ZoneId zone) {
        ZonedDateTime zdt = java.time.Instant.ofEpochMilli(epochMillis).atZone(zone);
        ZonedDateTime truncated = switch (this) {
            case SECOND -> zdt.truncatedTo(ChronoUnit.SECONDS);
            case MINUTE -> zdt.truncatedTo(ChronoUnit.MINUTES);
            case HOUR -> zdt.truncatedTo(ChronoUnit.HOURS);
            case DAY -> ZonedDateTime.of(zdt.toLocalDate(), java.time.LocalTime.MIDNIGHT, zone);
            case WEEK -> {
                java.time.LocalDate date = zdt.toLocalDate();
                int deltaDays = date.getDayOfWeek().getValue() - DayOfWeek.MONDAY.getValue();
                yield ZonedDateTime.of(date.minusDays(deltaDays), java.time.LocalTime.MIDNIGHT, zone);
            }
            case MONTH -> ZonedDateTime.of(zdt.toLocalDate().withDayOfMonth(1), java.time.LocalTime.MIDNIGHT, zone);
            case QUARTER -> {
                int qMonth = ((zdt.getMonthValue() - 1) / 3) * 3 + 1;
                yield ZonedDateTime.of(zdt.toLocalDate().withMonth(qMonth).withDayOfMonth(1), java.time.LocalTime.MIDNIGHT, zone);
            }
            case YEAR -> ZonedDateTime.of(zdt.toLocalDate().withDayOfYear(1), java.time.LocalTime.MIDNIGHT, zone);
        };
        return truncated.toInstant().toEpochMilli();
    }

    public long nextBucketMillis(long bucketStartMillis, ZoneId zone) {
        ZonedDateTime zdt = java.time.Instant.ofEpochMilli(bucketStartMillis).atZone(zone);
        ZonedDateTime next = switch (this) {
            case SECOND -> zdt.plusSeconds(1);
            case MINUTE -> zdt.plusMinutes(1);
            case HOUR -> zdt.plusHours(1);
            case DAY -> zdt.plusDays(1);
            case WEEK -> zdt.plusWeeks(1);
            case MONTH -> zdt.plusMonths(1);
            case QUARTER -> zdt.plusMonths(3);
            case YEAR -> zdt.plusYears(1);
        };
        return next.toInstant().toEpochMilli();
    }
}
