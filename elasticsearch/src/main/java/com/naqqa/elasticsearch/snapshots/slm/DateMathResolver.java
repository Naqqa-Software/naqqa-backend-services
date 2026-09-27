package com.naqqa.elasticsearch.snapshots.slm;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;

public final class DateMathResolver {

    private DateMathResolver() {
    }

    public static String resolve(String pattern, Instant now, ZoneId zone) {
        if (pattern.indexOf('<') < 0) {
            return pattern;
        }
        StringBuilder result = new StringBuilder();
        int i = 0;
        while (i < pattern.length()) {
            char c = pattern.charAt(i);
            if (c == '<') {
                int end = pattern.indexOf('>', i);
                if (end < 0) {
                    throw new IllegalArgumentException("Unclosed date-math expression in: " + pattern);
                }
                String expr = pattern.substring(i + 1, end);
                result.append(resolveExpression(expr, now, zone));
                i = end + 1;
            } else {
                result.append(c);
                i++;
            }
        }
        return result.toString();
    }

    private static String resolveExpression(String expr, Instant now, ZoneId zone) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < expr.length()) {
            char c = expr.charAt(i);
            if (c == '{') {
                int end = findMatchingBrace(expr, i);
                out.append(evaluateToken(expr.substring(i + 1, end), now, zone));
                i = end + 1;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    private static int findMatchingBrace(String s, int openIdx) {
        int depth = 0;
        for (int i = openIdx; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        throw new IllegalArgumentException("Unclosed '{' in date-math expression: " + s);
    }

    private static String evaluateToken(String token, Instant now, ZoneId zone) {
        String mathPart = token;
        String format = "yyyy.MM.dd";
        int braceIdx = token.indexOf('{');
        if (braceIdx >= 0) {
            int closeIdx = token.lastIndexOf('}');
            format = token.substring(braceIdx + 1, closeIdx);
            mathPart = token.substring(0, braceIdx);
        }
        if (!mathPart.startsWith("now")) {
            throw new IllegalArgumentException("Unsupported date-math expression: " + token);
        }
        ZonedDateTime time = now.atZone(zone);
        String rest = mathPart.substring(3);
        int idx = 0;
        while (idx < rest.length()) {
            char op = rest.charAt(idx);
            if (op == '+' || op == '-') {
                int j = idx + 1;
                while (j < rest.length() && Character.isDigit(rest.charAt(j))) {
                    j++;
                }
                int amount = Integer.parseInt(rest.substring(idx + 1, j));
                char unit = rest.charAt(j);
                time = applyMath(time, op == '-' ? -amount : amount, unit);
                idx = j + 1;
            } else if (op == '/') {
                char unit = rest.charAt(idx + 1);
                time = roundDown(time, unit);
                idx += 2;
            } else {
                throw new IllegalArgumentException("Unsupported date-math operator near: " + rest.substring(idx));
            }
        }
        return DateTimeFormatter.ofPattern(format).format(time);
    }

    private static ZonedDateTime applyMath(ZonedDateTime time, int amount, char unit) {
        return switch (unit) {
            case 'y' -> time.plusYears(amount);
            case 'M' -> time.plusMonths(amount);
            case 'w' -> time.plusWeeks(amount);
            case 'd' -> time.plusDays(amount);
            case 'h', 'H' -> time.plusHours(amount);
            case 'm' -> time.plusMinutes(amount);
            case 's' -> time.plusSeconds(amount);
            default -> throw new IllegalArgumentException("Unknown date-math unit: " + unit);
        };
    }

    private static ZonedDateTime roundDown(ZonedDateTime time, char unit) {
        return switch (unit) {
            case 'y' -> time.withDayOfYear(1).truncatedTo(ChronoUnit.DAYS);
            case 'M' -> time.withDayOfMonth(1).truncatedTo(ChronoUnit.DAYS);
            case 'w' -> time.with(TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY)).truncatedTo(ChronoUnit.DAYS);
            case 'd' -> time.truncatedTo(ChronoUnit.DAYS);
            case 'h', 'H' -> time.truncatedTo(ChronoUnit.HOURS);
            case 'm' -> time.truncatedTo(ChronoUnit.MINUTES);
            case 's' -> time.truncatedTo(ChronoUnit.SECONDS);
            default -> throw new IllegalArgumentException("Unknown date-math unit: " + unit);
        };
    }
}
