package com.naqqa.elasticsearch.common.time;

import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
import java.time.temporal.IsoFields;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public final class DateFormatters {

    private DateFormatters() {
    }

    private static final ZoneId UTC = ZoneOffset.UTC;
    private static final Map<String, DateFormatter> NAMED = new HashMap<>();

    private static DateTimeFormatter isoOptionalTime(int fractionDigits) {
        return new DateTimeFormatterBuilder()
            .append(DateTimeFormatter.ISO_LOCAL_DATE)
            .optionalStart()
            .appendLiteral('T')
            .appendValue(ChronoField.HOUR_OF_DAY, 2)
            .appendLiteral(':')
            .appendValue(ChronoField.MINUTE_OF_HOUR, 2)
            .optionalStart()
            .appendLiteral(':')
            .appendValue(ChronoField.SECOND_OF_MINUTE, 2)
            .optionalStart()
            .appendFraction(ChronoField.NANO_OF_SECOND, 0, fractionDigits, true)
            .optionalEnd()
            .optionalEnd()
            .optionalStart()
            .appendOffsetId()
            .optionalEnd()
            .optionalEnd()
            .parseDefaulting(ChronoField.HOUR_OF_DAY, 0)
            .parseDefaulting(ChronoField.MINUTE_OF_HOUR, 0)
            .parseDefaulting(ChronoField.SECOND_OF_MINUTE, 0)
            .parseDefaulting(ChronoField.NANO_OF_SECOND, 0)
            .toFormatter(Locale.ROOT);
    }

    private static DateTimeFormatter weekDateFormatter() {
        return new DateTimeFormatterBuilder()
            .appendValue(IsoFields.WEEK_BASED_YEAR, 4)
            .appendLiteral("-W")
            .appendValue(IsoFields.WEEK_OF_WEEK_BASED_YEAR, 2)
            .appendLiteral('-')
            .appendValue(ChronoField.DAY_OF_WEEK, 1)
            .parseDefaulting(ChronoField.HOUR_OF_DAY, 0)
            .parseDefaulting(ChronoField.MINUTE_OF_HOUR, 0)
            .parseDefaulting(ChronoField.SECOND_OF_MINUTE, 0)
            .parseDefaulting(ChronoField.NANO_OF_SECOND, 0)
            .toFormatter(Locale.ROOT);
    }

    static {
        NAMED.put("epoch_millis", new EpochMillisDateFormatter(UTC));
        NAMED.put("epoch_second", new EpochSecondDateFormatter(UTC));
        NAMED.put("strict_date_optional_time", new JavaDateFormatter("strict_date_optional_time", UTC, isoOptionalTime(3)));
        NAMED.put("date_optional_time", new JavaDateFormatter("date_optional_time", UTC, isoOptionalTime(3)));
        NAMED.put("strict_date_optional_time_nanos", new JavaDateFormatter("strict_date_optional_time_nanos", UTC, isoOptionalTime(9)));
        NAMED.put("basic_date", new JavaDateFormatter("uuuuMMdd", UTC));
        NAMED.put("date", new JavaDateFormatter("uuuu-MM-dd", UTC));
        NAMED.put("strict_date", new JavaDateFormatter("uuuu-MM-dd", UTC));
        NAMED.put("year_month_day", new JavaDateFormatter("uuuu-MM-dd", UTC));
        NAMED.put("date_time", new JavaDateFormatter("date_time", UTC, isoOptionalTime(3)));
        NAMED.put("date_hour_minute_second", new JavaDateFormatter("uuuu-MM-dd'T'HH:mm:ss", UTC));
        NAMED.put("week_date", new JavaDateFormatter("week_date", UTC, weekDateFormatter()));
    }

    public static DateFormatter byName(String name) {
        return NAMED.get(name);
    }
}
