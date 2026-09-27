package com.naqqa.elasticsearch.snapshots.slm;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import java.util.TreeSet;

public final class CronSchedule implements SlmSchedule {

    private static final long MAX_MINUTES_SEARCH = 4L * 366 * 24 * 60;

    private final String expression;
    private final CronField minutes;
    private final CronField hours;
    private final CronField daysOfMonth;
    private final CronField months;
    private final CronField daysOfWeek;

    public CronSchedule(String expression) {
        this.expression = expression;
        String[] fields = expression.trim().split("\\s+");
        if (fields.length != 5) {
            throw new IllegalArgumentException("Cron expression must have 5 fields (minute hour dom month dow): " + expression);
        }
        this.minutes = CronField.parse(fields[0], 0, 59, false);
        this.hours = CronField.parse(fields[1], 0, 23, false);
        this.daysOfMonth = CronField.parse(fields[2], 1, 31, false);
        this.months = CronField.parse(fields[3], 1, 12, false);
        this.daysOfWeek = CronField.parse(fields[4], 0, 6, true);
    }

    public String expression() {
        return expression;
    }

    @Override
    public Instant nextFireTime(Instant after) {
        ZonedDateTime candidate = after.atZone(ZoneOffset.UTC)
                .truncatedTo(ChronoUnit.MINUTES)
                .plusMinutes(1);
        for (long i = 0; i < MAX_MINUTES_SEARCH; i++) {
            if (matches(candidate)) {
                return candidate.toInstant();
            }
            candidate = candidate.plusMinutes(1);
        }
        throw new IllegalStateException("No fire time found within search horizon for cron: " + expression);
    }

    private boolean matches(ZonedDateTime time) {
        if (!minutes.matches(time.getMinute())) {
            return false;
        }
        if (!hours.matches(time.getHour())) {
            return false;
        }
        if (!months.matches(time.getMonthValue())) {
            return false;
        }
        int dom = time.getDayOfMonth();
        int dow = time.getDayOfWeek().getValue() % 7;
        boolean domRestricted = !daysOfMonth.wildcard();
        boolean dowRestricted = !daysOfWeek.wildcard();
        if (domRestricted && dowRestricted) {
            return daysOfMonth.matches(dom) || daysOfWeek.matches(dow);
        }
        if (domRestricted) {
            return daysOfMonth.matches(dom);
        }
        if (dowRestricted) {
            return daysOfWeek.matches(dow);
        }
        return true;
    }

    private static final class CronField {
        private final Set<Integer> values;
        private final boolean wildcard;

        private CronField(Set<Integer> values, boolean wildcard) {
            this.values = values;
            this.wildcard = wildcard;
        }

        boolean matches(int value) {
            return values.contains(value);
        }

        boolean wildcard() {
            return wildcard;
        }

        static CronField parse(String field, int min, int max, boolean isDayOfWeek) {
            boolean wildcard = false;
            Set<Integer> values = new TreeSet<>();
            for (String part : field.split(",")) {
                if (part.equals("*")) {
                    wildcard = true;
                    for (int v = min; v <= max; v++) {
                        values.add(v);
                    }
                    continue;
                }
                String base = part;
                int step = 1;
                int slash = part.indexOf('/');
                if (slash >= 0) {
                    base = part.substring(0, slash);
                    step = Integer.parseInt(part.substring(slash + 1));
                }
                int start;
                int end;
                if (base.equals("*")) {
                    start = min;
                    end = max;
                } else if (base.contains("-")) {
                    int dash = base.indexOf('-');
                    start = Integer.parseInt(base.substring(0, dash));
                    end = Integer.parseInt(base.substring(dash + 1));
                } else {
                    start = Integer.parseInt(base);
                    end = slash >= 0 ? max : start;
                }
                for (int v = start; v <= end; v += step) {
                    values.add(normalize(v, min, max, isDayOfWeek));
                }
            }
            return new CronField(values, wildcard);
        }

        private static int normalize(int v, int min, int max, boolean isDayOfWeek) {
            if (isDayOfWeek && v == 7) {
                return 0;
            }
            if (v < min || v > max) {
                throw new IllegalArgumentException("Value " + v + " out of range [" + min + "," + max + "]");
            }
            return v;
        }
    }
}
