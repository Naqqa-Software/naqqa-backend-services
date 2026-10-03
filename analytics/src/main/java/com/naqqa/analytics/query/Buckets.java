package com.naqqa.analytics.query;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;

public final class Buckets {

    private static final DateTimeFormatter HOUR = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:00");
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyy-MM");

    private Buckets() {
    }

    public static String key(Instant ts, String granularity, ZoneId zone) {
        LocalDateTime t = LocalDateTime.ofInstant(ts, zone);
        return switch (granularity) {
            case AnalyticsQuery.HOUR -> HOUR.format(t);
            case AnalyticsQuery.WEEK -> t.toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toString();
            case AnalyticsQuery.MONTH -> MONTH.format(t);
            default -> t.toLocalDate().toString();
        };
    }

    public static List<String> range(LocalDate from, LocalDate to, String granularity) {
        List<String> out = new ArrayList<>();
        switch (granularity) {
            case AnalyticsQuery.HOUR -> {
                for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
                    for (int h = 0; h < 24; h++) {
                        out.add(HOUR.format(d.atTime(h, 0)));
                    }
                }
            }
            case AnalyticsQuery.WEEK -> {
                for (LocalDate d = from.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)); !d.isAfter(to); d = d.plusWeeks(1)) {
                    out.add(d.toString());
                }
            }
            case AnalyticsQuery.MONTH -> {
                for (LocalDate d = from.withDayOfMonth(1); !d.isAfter(to); d = d.plusMonths(1)) {
                    out.add(MONTH.format(d));
                }
            }
            default -> {
                for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
                    out.add(d.toString());
                }
            }
        }
        return out;
    }

    public static String week(Instant ts, ZoneId zone) {
        return key(ts, AnalyticsQuery.WEEK, zone);
    }
}
