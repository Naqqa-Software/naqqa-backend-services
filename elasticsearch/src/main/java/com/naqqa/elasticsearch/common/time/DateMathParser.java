package com.naqqa.elasticsearch.common.time;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;

public final class DateMathParser {

    private final DateFormatter formatter;

    public DateMathParser(DateFormatter formatter) {
        this.formatter = formatter;
    }

    public long parse(String text, long now, boolean roundUp, ZoneId zone) {
        ZoneId effectiveZone = zone == null ? ZoneId.of("UTC") : zone;
        String mathString;
        long anchorMillis;
        if (text.startsWith("now")) {
            anchorMillis = now;
            mathString = text.substring(3);
        } else {
            int index = text.indexOf("||");
            String anchorPart;
            if (index == -1) {
                anchorPart = text;
                mathString = "";
            } else {
                anchorPart = text.substring(0, index);
                mathString = text.substring(index + 2);
            }
            anchorMillis = formatter.withZone(effectiveZone).parseMillis(anchorPart);
        }
        if (mathString.isEmpty()) {
            return anchorMillis;
        }
        return parseMath(mathString, anchorMillis, roundUp, effectiveZone);
    }

    private long parseMath(String mathString, long time, boolean roundUp, ZoneId zone) {
        ZonedDateTime dateTime = Instant.ofEpochMilli(time).atZone(zone);
        int i = 0;
        int len = mathString.length();
        while (i < len) {
            char c = mathString.charAt(i);
            if (c == '/') {
                i++;
                int start = i;
                while (i < len && Character.isLetter(mathString.charAt(i))) {
                    i++;
                }
                String unit = mathString.substring(start, i);
                dateTime = roundToUnit(dateTime, unit, roundUp);
            } else {
                boolean negative = c == '-';
                if (c != '+' && c != '-') {
                    throw new IllegalArgumentException("operator not supported for date math [" + mathString + "]");
                }
                i++;
                int start = i;
                while (i < len && Character.isDigit(mathString.charAt(i))) {
                    i++;
                }
                if (start == i) {
                    throw new IllegalArgumentException("truncated date math [" + mathString + "]");
                }
                long num = Long.parseLong(mathString.substring(start, i));
                if (negative) {
                    num = -num;
                }
                if (i >= len) {
                    throw new IllegalArgumentException("truncated date math [" + mathString + "]");
                }
                char unitChar = mathString.charAt(i);
                i++;
                dateTime = applyUnit(dateTime, unitChar, num);
            }
        }
        return dateTime.toInstant().toEpochMilli();
    }

    private ZonedDateTime applyUnit(ZonedDateTime dateTime, char unit, long amount) {
        return switch (unit) {
            case 'y' -> dateTime.plusYears(amount);
            case 'M' -> dateTime.plusMonths(amount);
            case 'w' -> dateTime.plusWeeks(amount);
            case 'd' -> dateTime.plusDays(amount);
            case 'h', 'H' -> dateTime.plusHours(amount);
            case 'm' -> dateTime.plusMinutes(amount);
            case 's' -> dateTime.plusSeconds(amount);
            default -> throw new IllegalArgumentException("unit [" + unit + "] not supported for date math");
        };
    }

    private ZonedDateTime roundToUnit(ZonedDateTime dateTime, String unit, boolean roundUp) {
        ZonedDateTime floor = floorToUnit(dateTime, unit);
        if (!roundUp) {
            return floor;
        }
        ZonedDateTime next = applyUnit(floor, unitChar(unit), 1);
        return next.minus(1, ChronoUnit.MILLIS);
    }

    private char unitChar(String unit) {
        return switch (unit) {
            case "y" -> 'y';
            case "M" -> 'M';
            case "w" -> 'w';
            case "d" -> 'd';
            case "h", "H" -> 'h';
            case "m" -> 'm';
            case "s" -> 's';
            default -> throw new IllegalArgumentException("unit [" + unit + "] not supported for date math rounding");
        };
    }

    private ZonedDateTime floorToUnit(ZonedDateTime dateTime, String unit) {
        return switch (unit) {
            case "y" -> dateTime.withDayOfYear(1).truncatedTo(ChronoUnit.DAYS);
            case "M" -> dateTime.withDayOfMonth(1).truncatedTo(ChronoUnit.DAYS);
            case "w" -> dateTime.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).truncatedTo(ChronoUnit.DAYS);
            case "d" -> dateTime.truncatedTo(ChronoUnit.DAYS);
            case "h", "H" -> dateTime.truncatedTo(ChronoUnit.HOURS);
            case "m" -> dateTime.truncatedTo(ChronoUnit.MINUTES);
            case "s" -> dateTime.truncatedTo(ChronoUnit.SECONDS);
            default -> throw new IllegalArgumentException("unit [" + unit + "] not supported for date math rounding");
        };
    }
}
