package com.naqqa.elasticsearch.ingest.processor;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

public final class DateFormats {

    private DateFormats() {
    }

    public static ZonedDateTime parseAny(Object raw, List<String> formats, ZoneId zone, Locale locale) {
        Exception lastError = null;
        for (String format : formats) {
            try {
                return parse(raw, format, zone, locale);
            } catch (Exception e) {
                lastError = e;
            }
        }
        throw new IllegalArgumentException("unable to parse date [" + raw + "] with formats " + formats, lastError);
    }

    public static ZonedDateTime parse(Object raw, String format, ZoneId zone, Locale locale) {
        String value = String.valueOf(raw);
        switch (format) {
            case "ISO8601":
                try {
                    return ZonedDateTime.parse(value);
                } catch (Exception e) {
                    return java.time.LocalDateTime.parse(value, DateTimeFormatter.ISO_LOCAL_DATE_TIME).atZone(zone);
                }
            case "UNIX": {
                double seconds = Double.parseDouble(value);
                long millis = (long) (seconds * 1000);
                return Instant.ofEpochMilli(millis).atZone(zone);
            }
            case "UNIX_MS":
                return Instant.ofEpochMilli(Long.parseLong(value)).atZone(zone);
            case "TAI64N":
                return parseTai64n(value).atZone(zone);
            default:
                DateTimeFormatter formatter = DateTimeFormatter.ofPattern(format, locale == null ? Locale.ROOT : locale);
                try {
                    return ZonedDateTime.parse(value, formatter.withZone(zone));
                } catch (Exception e) {
                    java.time.temporal.TemporalAccessor ta = formatter.parseBest(value, ZonedDateTime::from, java.time.LocalDateTime::from, java.time.LocalDate::from);
                    if (ta instanceof ZonedDateTime zdt) {
                        return zdt;
                    }
                    if (ta instanceof java.time.LocalDateTime ldt) {
                        return ldt.atZone(zone);
                    }
                    if (ta instanceof java.time.LocalDate ld) {
                        return ld.atStartOfDay(zone);
                    }
                    throw new IllegalArgumentException("cannot parse [" + value + "] with pattern [" + format + "]");
                }
        }
    }

    public static Instant parseTai64n(String value) {
        String hex = value.startsWith("@") ? value.substring(1) : value;
        if (hex.length() != 24) {
            throw new IllegalArgumentException("invalid TAI64N value [" + value + "]");
        }
        long secondsSinceTai = Long.parseLong(hex.substring(0, 16), 16);
        long nanos = Long.parseLong(hex.substring(16), 16);
        long unixSeconds = secondsSinceTai - 4611686018427387904L - 10L;
        return Instant.ofEpochSecond(unixSeconds, nanos);
    }

    public static String formatTai64n(Instant instant) {
        long secondsSinceTai = instant.getEpochSecond() + 4611686018427387904L + 10L;
        long nanos = instant.getNano();
        return String.format("@%016x%08x", secondsSinceTai, nanos);
    }
}
