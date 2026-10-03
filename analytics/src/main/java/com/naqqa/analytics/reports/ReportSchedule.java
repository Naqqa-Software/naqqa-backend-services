package com.naqqa.analytics.reports;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;

public final class ReportSchedule {

    public static final String WEEKLY = "WEEKLY";
    public static final String MONTHLY = "MONTHLY";

    private ReportSchedule() {
    }

    public static ZonedDateTime next(String frequency, ZonedDateTime now, LocalTime at) {
        LocalDate d = now.toLocalDate();
        ZoneId zone = now.getZone();
        if (MONTHLY.equals(frequency)) {
            LocalDate first = d.withDayOfMonth(1);
            ZonedDateTime candidate = first.atTime(at).atZone(zone);
            return candidate.isAfter(now) ? candidate : first.plusMonths(1).atTime(at).atZone(zone);
        }
        LocalDate monday = d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        ZonedDateTime candidate = monday.atTime(at).atZone(zone);
        return candidate.isAfter(now) ? candidate : monday.plusWeeks(1).atTime(at).atZone(zone);
    }

    public static LocalDate[] period(String frequency, LocalDate runDay) {
        if (MONTHLY.equals(frequency)) {
            LocalDate start = runDay.withDayOfMonth(1).minusMonths(1);
            return new LocalDate[]{start, start.with(TemporalAdjusters.lastDayOfMonth())};
        }
        LocalDate monday = runDay.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).minusWeeks(1);
        return new LocalDate[]{monday, monday.plusDays(6)};
    }
}
