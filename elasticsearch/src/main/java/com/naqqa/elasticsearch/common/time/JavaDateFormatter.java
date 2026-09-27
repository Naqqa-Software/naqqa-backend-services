package com.naqqa.elasticsearch.common.time;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
import java.time.temporal.TemporalAccessor;
import java.util.Locale;

final class JavaDateFormatter implements DateFormatter {

    private final String pattern;
    private final ZoneId zone;
    private final DateTimeFormatter formatter;

    JavaDateFormatter(String pattern, ZoneId zone) {
        this.pattern = pattern;
        this.zone = zone;
        this.formatter = build(pattern, zone);
    }

    JavaDateFormatter(String pattern, ZoneId zone, DateTimeFormatter formatter) {
        this.pattern = pattern;
        this.zone = zone;
        this.formatter = formatter.withZone(zone);
    }

    private static DateTimeFormatter build(String pattern, ZoneId zone) {
        DateTimeFormatterBuilder builder = new DateTimeFormatterBuilder();
        builder.appendPattern(pattern);
        builder.parseDefaulting(ChronoField.YEAR, 1970);
        builder.parseDefaulting(ChronoField.MONTH_OF_YEAR, 1);
        builder.parseDefaulting(ChronoField.DAY_OF_MONTH, 1);
        builder.parseDefaulting(ChronoField.HOUR_OF_DAY, 0);
        builder.parseDefaulting(ChronoField.MINUTE_OF_HOUR, 0);
        builder.parseDefaulting(ChronoField.SECOND_OF_MINUTE, 0);
        builder.parseDefaulting(ChronoField.NANO_OF_SECOND, 0);
        return builder.toFormatter(Locale.ROOT).withZone(zone);
    }

    @Override
    public Instant parse(String input) {
        TemporalAccessor ta = formatter.parse(input);
        try {
            return ZonedDateTime.from(ta).toInstant();
        } catch (DateTimeException e) {
            LocalDate date = LocalDate.from(ta);
            LocalTime time = LocalTime.from(ta);
            return ZonedDateTime.of(date, time, zone).toInstant();
        }
    }

    @Override
    public String format(Instant instant) {
        return formatter.format(instant.atZone(zone));
    }

    @Override
    public DateFormatter withZone(ZoneId newZone) {
        return new JavaDateFormatter(pattern, newZone, formatter.withZone(newZone));
    }

    @Override
    public String pattern() {
        return pattern;
    }

    @Override
    public ZoneId zone() {
        return zone;
    }
}
