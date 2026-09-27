package com.naqqa.elasticsearch.common.cron;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

public final class CronExpression {

    private static final int MIN_YEAR = 1970;
    private static final int MAX_YEAR = 2199;
    private static final int MAX_DAYS_SCAN = 8 * 366 + 10;

    private static final Map<String, Integer> MONTH_NAMES = new HashMap<>();
    private static final Map<String, Integer> DOW_NAMES = new HashMap<>();

    static {
        String[] months = {"JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC"};
        for (int i = 0; i < months.length; i++) {
            MONTH_NAMES.put(months[i], i + 1);
        }
        String[] dows = {"SUN", "MON", "TUE", "WED", "THU", "FRI", "SAT"};
        for (int i = 0; i < dows.length; i++) {
            DOW_NAMES.put(dows[i], i + 1);
        }
    }

    private final String expression;
    private TreeSet<Integer> seconds;
    private TreeSet<Integer> minutes;
    private TreeSet<Integer> hours;
    private TreeSet<Integer> daysOfMonth;
    private TreeSet<Integer> months;
    private TreeSet<Integer> daysOfWeek;
    private TreeSet<Integer> years;

    private boolean dayOfMonthSpecified;
    private boolean dayOfWeekSpecified;

    private boolean lastDayOfMonth;
    private int lastDayOffset;
    private boolean nearestWeekday;

    private boolean lastDayOfWeek;
    private int nthDayOfWeek;

    public CronExpression(String expression) {
        this.expression = expression;
        parse(expression);
    }

    public String getExpression() {
        return expression;
    }

    private void parse(String expr) {
        String[] parts = expr.trim().split("\\s+");
        if (parts.length < 6 || parts.length > 7) {
            throw new IllegalArgumentException("cron expression must have 6 or 7 fields: [" + expr + "]");
        }
        seconds = parseNumeric(parts[0], 0, 59, null);
        minutes = parseNumeric(parts[1], 0, 59, null);
        hours = parseNumeric(parts[2], 0, 23, null);
        daysOfMonth = parseDayOfMonth(parts[3]);
        months = parseNumeric(parts[4], 1, 12, MONTH_NAMES);
        daysOfWeek = parseDayOfWeek(parts[5]);
        years = parts.length == 7 ? parseNumeric(parts[6], MIN_YEAR, MAX_YEAR, null) : fullRange(MIN_YEAR, MAX_YEAR);
        if (!dayOfMonthSpecified && !dayOfWeekSpecified) {
            dayOfMonthSpecified = true;
        }
        if (dayOfMonthSpecified && dayOfWeekSpecified) {
            throw new IllegalArgumentException(
                "day-of-month and day-of-week fields are mutually exclusive: exactly one must be '?' in [" + expr + "]");
        }
    }

    private static TreeSet<Integer> fullRange(int min, int max) {
        TreeSet<Integer> set = new TreeSet<>();
        for (int i = min; i <= max; i++) {
            set.add(i);
        }
        return set;
    }

    private TreeSet<Integer> parseNumeric(String field, int min, int max, Map<String, Integer> names) {
        TreeSet<Integer> set = new TreeSet<>();
        for (String token : field.split(",")) {
            parseNumericToken(token.trim(), min, max, names, set);
        }
        return set;
    }

    private void parseNumericToken(String token, int min, int max, Map<String, Integer> names, TreeSet<Integer> set) {
        if (token.equals("?")) {
            for (int i = min; i <= max; i++) {
                set.add(i);
            }
            return;
        }
        int step = 1;
        String rangePart = token;
        int slash = token.indexOf('/');
        if (slash >= 0) {
            rangePart = token.substring(0, slash);
            step = Integer.parseInt(token.substring(slash + 1));
        }
        int start;
        int end;
        if (rangePart.equals("*") || rangePart.isEmpty()) {
            start = min;
            end = max;
        } else {
            int dash = rangePart.indexOf('-');
            if (dash > 0) {
                start = resolveValue(rangePart.substring(0, dash), names);
                end = resolveValue(rangePart.substring(dash + 1), names);
            } else {
                start = resolveValue(rangePart, names);
                end = (slash >= 0) ? max : start;
            }
        }
        if (start > end) {
            for (int i = start; i <= max; i += step) {
                set.add(i);
            }
            for (int i = min; i <= end; i += step) {
                set.add(i);
            }
        } else {
            for (int i = start; i <= end; i += step) {
                set.add(i);
            }
        }
    }

    private int resolveValue(String s, Map<String, Integer> names) {
        s = s.trim();
        if (names != null) {
            Integer v = names.get(s.toUpperCase(Locale.ROOT));
            if (v != null) {
                return v;
            }
        }
        return Integer.parseInt(s);
    }

    private TreeSet<Integer> parseDayOfMonth(String field) {
        TreeSet<Integer> set = new TreeSet<>();
        boolean specified = false;
        for (String rawToken : field.split(",")) {
            String token = rawToken.trim();
            String upper = token.toUpperCase(Locale.ROOT);
            if (token.equals("?")) {
                continue;
            }
            specified = true;
            if (upper.equals("L")) {
                lastDayOfMonth = true;
            } else if (upper.startsWith("L-")) {
                lastDayOfMonth = true;
                lastDayOffset = Integer.parseInt(upper.substring(2));
            } else if (upper.equals("LW") || upper.equals("WL")) {
                lastDayOfMonth = true;
                nearestWeekday = true;
            } else if (upper.endsWith("W")) {
                nearestWeekday = true;
                set.add(Integer.parseInt(upper.substring(0, upper.length() - 1)));
            } else {
                parseNumericToken(token, 1, 31, null, set);
            }
        }
        this.dayOfMonthSpecified = specified;
        if (set.isEmpty() && !lastDayOfMonth) {
            set.add(1);
        }
        return set;
    }

    private TreeSet<Integer> parseDayOfWeek(String field) {
        TreeSet<Integer> set = new TreeSet<>();
        boolean specified = false;
        for (String rawToken : field.split(",")) {
            String token = rawToken.trim();
            String upper = token.toUpperCase(Locale.ROOT);
            if (token.equals("?")) {
                continue;
            }
            specified = true;
            int hash = upper.indexOf('#');
            if (hash > 0) {
                nthDayOfWeek = Integer.parseInt(upper.substring(hash + 1));
                set.add(resolveValue(upper.substring(0, hash), DOW_NAMES));
            } else if (upper.endsWith("L") && upper.length() > 1 && !upper.equals("L")) {
                lastDayOfWeek = true;
                set.add(resolveValue(upper.substring(0, upper.length() - 1), DOW_NAMES));
            } else if (upper.equals("L")) {
                lastDayOfWeek = true;
                set.add(7);
            } else {
                parseNumericToken(token, 1, 7, DOW_NAMES, set);
            }
        }
        this.dayOfWeekSpecified = specified;
        if (set.isEmpty()) {
            for (int i = 1; i <= 7; i++) {
                set.add(i);
            }
        }
        return set;
    }

    private static int quartzDayOfWeek(LocalDate date) {
        int iso = date.getDayOfWeek().getValue();
        return (iso % 7) + 1;
    }

    private static int lastDayOfMonth(int year, int month) {
        return LocalDate.of(year, month, 1).lengthOfMonth();
    }

    private boolean dayOfMonthMatches(LocalDate date) {
        int day = date.getDayOfMonth();
        int lastDay = lastDayOfMonth(date.getYear(), date.getMonthValue());
        if (lastDayOfMonth) {
            int target = lastDay - lastDayOffset;
            if (nearestWeekday) {
                target = nearestWeekday(date.getYear(), date.getMonthValue(), target, lastDay);
            }
            return day == target;
        }
        if (nearestWeekday) {
            int base = daysOfMonth.first();
            int target = nearestWeekday(date.getYear(), date.getMonthValue(), base, lastDay);
            return day == target;
        }
        return daysOfMonth.contains(day);
    }

    private static int nearestWeekday(int year, int month, int day, int lastDay) {
        if (day < 1) {
            day = 1;
        }
        if (day > lastDay) {
            day = lastDay;
        }
        LocalDate d = LocalDate.of(year, month, day);
        java.time.DayOfWeek dow = d.getDayOfWeek();
        if (dow == java.time.DayOfWeek.SATURDAY) {
            return day == 1 ? day + 2 : day - 1;
        }
        if (dow == java.time.DayOfWeek.SUNDAY) {
            return day == lastDay ? day - 2 : day + 1;
        }
        return day;
    }

    private boolean dayOfWeekMatches(LocalDate date) {
        int dow = quartzDayOfWeek(date);
        int target = daysOfWeek.first();
        if (lastDayOfWeek) {
            int lastDay = lastDayOfMonth(date.getYear(), date.getMonthValue());
            int day = date.getDayOfMonth();
            return dow == target && (day + 7) > lastDay;
        }
        if (nthDayOfWeek != 0) {
            if (dow != target) {
                return false;
            }
            int occurrence = ((date.getDayOfMonth() - 1) / 7) + 1;
            return occurrence == nthDayOfWeek;
        }
        return daysOfWeek.contains(dow);
    }

    private boolean dateMatches(LocalDate date) {
        if (!years.contains(date.getYear())) {
            return false;
        }
        if (!months.contains(date.getMonthValue())) {
            return false;
        }
        if (dayOfMonthSpecified) {
            return dayOfMonthMatches(date);
        }
        return dayOfWeekMatches(date);
    }

    private int[] firstTimeAtOrAfter(int hour, int min, int sec) {
        Integer s = seconds.ceiling(sec);
        if (s == null) {
            s = seconds.first();
            min = min + 1;
        }
        Integer m = minutes.ceiling(min);
        if (m == null) {
            m = minutes.first();
            hour = hour + 1;
            s = seconds.first();
        } else if (m > min) {
            s = seconds.first();
        }
        Integer h = hours.ceiling(hour);
        if (h == null) {
            return null;
        }
        if (h > hour) {
            m = minutes.first();
            s = seconds.first();
        }
        return new int[] {h, m, s};
    }

    public ZonedDateTime nextValidTimeAfter(ZonedDateTime after) {
        ZonedDateTime start = after.withNano(0).plusSeconds(1);
        LocalDate day = start.toLocalDate();
        boolean first = true;
        for (int i = 0; i < MAX_DAYS_SCAN; i++) {
            if (day.getYear() > MAX_YEAR) {
                return null;
            }
            if (dateMatches(day)) {
                int[] time = first
                    ? firstTimeAtOrAfter(start.getHour(), start.getMinute(), start.getSecond())
                    : new int[] {hours.first(), minutes.first(), seconds.first()};
                if (time != null) {
                    LocalDateTime ldt = LocalDateTime.of(day, LocalTime.of(time[0], time[1], time[2]));
                    return ZonedDateTime.of(ldt, start.getZone());
                }
            }
            first = false;
            day = day.plusDays(1);
        }
        return null;
    }

    public boolean matches(ZonedDateTime time) {
        ZonedDateTime truncated = time.withNano(0);
        ZonedDateTime next = nextValidTimeAfter(truncated.minusSeconds(1));
        return next != null && next.equals(truncated);
    }

    @Override
    public String toString() {
        return expression;
    }
}
