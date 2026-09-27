package com.naqqa.elasticsearch.common.unit;

import java.util.concurrent.TimeUnit;

public final class TimeValue implements Comparable<TimeValue> {

    public static final TimeValue MINUS_ONE = new TimeValue(-1, TimeUnit.MILLISECONDS);
    public static final TimeValue ZERO = new TimeValue(0, TimeUnit.MILLISECONDS);

    private final long duration;
    private final TimeUnit unit;

    public TimeValue(long duration, TimeUnit unit) {
        this.duration = duration;
        this.unit = unit;
    }

    public static TimeValue timeValueMillis(long millis) {
        return new TimeValue(millis, TimeUnit.MILLISECONDS);
    }

    public static TimeValue timeValueSeconds(long seconds) {
        return new TimeValue(seconds, TimeUnit.SECONDS);
    }

    public static TimeValue timeValueMinutes(long minutes) {
        return new TimeValue(minutes, TimeUnit.MINUTES);
    }

    public static TimeValue timeValueHours(long hours) {
        return new TimeValue(hours, TimeUnit.HOURS);
    }

    public static TimeValue timeValueNanos(long nanos) {
        return new TimeValue(nanos, TimeUnit.NANOSECONDS);
    }

    public long duration() {
        return duration;
    }

    public TimeUnit timeUnit() {
        return unit;
    }

    public long nanos() {
        return unit.toNanos(duration);
    }

    public long micros() {
        return unit.toMicros(duration);
    }

    public long millis() {
        return unit.toMillis(duration);
    }

    public long seconds() {
        return unit.toSeconds(duration);
    }

    public long minutes() {
        return unit.toMinutes(duration);
    }

    public long hours() {
        return unit.toHours(duration);
    }

    public double secondsFrac() {
        return millis() / 1000d;
    }

    public double minutesFrac() {
        return millis() / (1000d * 60);
    }

    public static TimeValue parseTimeValue(String value, String settingName) {
        return parseTimeValue(value, null, settingName);
    }

    public static TimeValue parseTimeValue(String value, TimeValue defaultValue, String settingName) {
        if (value == null) {
            return defaultValue;
        }
        String sValue = value.trim();
        if (sValue.equals("-1")) {
            return MINUS_ONE;
        }
        if (sValue.isEmpty()) {
            throw new IllegalArgumentException("failed to parse [" + settingName + "]: empty value");
        }
        try {
            if (sValue.endsWith("nanos")) {
                return new TimeValue(parseNum(sValue, 5), TimeUnit.NANOSECONDS);
            } else if (sValue.endsWith("micros")) {
                return new TimeValue(parseNum(sValue, 6), TimeUnit.MICROSECONDS);
            } else if (sValue.endsWith("ms")) {
                return new TimeValue(parseNum(sValue, 2), TimeUnit.MILLISECONDS);
            } else if (sValue.endsWith("s")) {
                return new TimeValue(parseNum(sValue, 1), TimeUnit.SECONDS);
            } else if (sValue.endsWith("m")) {
                return new TimeValue(parseNum(sValue, 1), TimeUnit.MINUTES);
            } else if (sValue.endsWith("h")) {
                return new TimeValue(parseNum(sValue, 1), TimeUnit.HOURS);
            } else if (sValue.endsWith("d")) {
                return new TimeValue(parseNum(sValue, 1), TimeUnit.DAYS);
            } else if (sValue.equals("0")) {
                return ZERO;
            } else {
                throw new IllegalArgumentException(
                    "failed to parse setting [" + settingName + "] with value [" + sValue + "] as a time value: unit is missing or unrecognized"
                );
            }
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                "failed to parse setting [" + settingName + "] with value [" + sValue + "] as a time value: " + e.getMessage(), e
            );
        }
    }

    private static long parseNum(String value, int suffixLength) {
        String numPart = value.substring(0, value.length() - suffixLength);
        return Long.parseLong(numPart.trim());
    }

    public String getStringRep() {
        if (duration < 0) {
            return "-1";
        }
        switch (unit) {
            case NANOSECONDS -> {
                return duration + "nanos";
            }
            case MICROSECONDS -> {
                return duration + "micros";
            }
            case MILLISECONDS -> {
                return duration + "ms";
            }
            case SECONDS -> {
                return duration + "s";
            }
            case MINUTES -> {
                return duration + "m";
            }
            case HOURS -> {
                return duration + "h";
            }
            case DAYS -> {
                return duration + "d";
            }
            default -> throw new IllegalArgumentException("unknown unit: " + unit);
        }
    }

    @Override
    public String toString() {
        if (duration < 0) {
            return "-1";
        }
        return getStringRep();
    }

    @Override
    public int compareTo(TimeValue other) {
        return Long.compare(nanos(), other.nanos());
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof TimeValue other)) {
            return false;
        }
        return nanos() == other.nanos();
    }

    @Override
    public int hashCode() {
        return Long.hashCode(nanos());
    }
}
