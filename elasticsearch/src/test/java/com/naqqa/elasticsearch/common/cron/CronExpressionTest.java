package com.naqqa.elasticsearch.common.cron;

import static com.naqqa.elasticsearch.test.Assert.assertEquals;
import static com.naqqa.elasticsearch.test.Assert.assertTrue;

import com.naqqa.elasticsearch.test.Test;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

public class CronExpressionTest {

    private static ZonedDateTime at(int y, int mo, int d, int h, int mi, int s) {
        return ZonedDateTime.of(y, mo, d, h, mi, s, 0, ZoneOffset.UTC);
    }

    @Test
    public void everyMinuteAdvancesCorrectly() {
        CronExpression cron = new CronExpression("0 * * * * ?");
        ZonedDateTime start = at(2024, 1, 1, 10, 30, 15);
        ZonedDateTime next = cron.nextValidTimeAfter(start);
        assertEquals(at(2024, 1, 1, 10, 31, 0), next);
        ZonedDateTime next2 = cron.nextValidTimeAfter(next);
        assertEquals(at(2024, 1, 1, 10, 32, 0), next2);
    }

    @Test
    public void specificTimeDaily() {
        CronExpression cron = new CronExpression("0 30 2 * * ?");
        ZonedDateTime start = at(2024, 3, 10, 5, 0, 0);
        ZonedDateTime next = cron.nextValidTimeAfter(start);
        assertEquals(at(2024, 3, 11, 2, 30, 0), next);
        ZonedDateTime beforeToday = at(2024, 3, 10, 0, 0, 0);
        assertEquals(at(2024, 3, 10, 2, 30, 0), cron.nextValidTimeAfter(beforeToday));
    }

    @Test
    public void everyFifteenMinutesDuringBusinessHours() {
        CronExpression cron = new CronExpression("0 0/15 9-17 ? * MON-FRI");
        ZonedDateTime friday1650 = at(2024, 1, 5, 16, 50, 0);
        ZonedDateTime next = cron.nextValidTimeAfter(friday1650);
        assertEquals(at(2024, 1, 5, 17, 0, 0), next);
        ZonedDateTime friday1750 = at(2024, 1, 5, 17, 50, 0);
        ZonedDateTime nextAfterHours = cron.nextValidTimeAfter(friday1750);
        assertEquals(at(2024, 1, 8, 9, 0, 0), nextAfterHours);
    }

    @Test
    public void monthAndSpecificDayOfMonth() {
        CronExpression cron = new CronExpression("0 0 12 15 * ?");
        ZonedDateTime start = at(2024, 1, 1, 0, 0, 0);
        assertEquals(at(2024, 1, 15, 12, 0, 0), cron.nextValidTimeAfter(start));
        assertEquals(at(2024, 2, 15, 12, 0, 0), cron.nextValidTimeAfter(at(2024, 1, 15, 12, 0, 1)));
    }

    @Test
    public void lastDayOfMonth() {
        CronExpression cron = new CronExpression("0 0 0 L * ?");
        assertEquals(at(2024, 2, 29, 0, 0, 0), cron.nextValidTimeAfter(at(2024, 2, 1, 0, 0, 0)));
        assertEquals(at(2024, 4, 30, 0, 0, 0), cron.nextValidTimeAfter(at(2024, 4, 1, 0, 0, 0)));
        assertEquals(at(2023, 2, 28, 0, 0, 0), cron.nextValidTimeAfter(at(2023, 2, 1, 0, 0, 0)));
    }

    @Test
    public void lastFridayOfMonth() {
        CronExpression cron = new CronExpression("0 0 0 ? * 6L");
        ZonedDateTime result = cron.nextValidTimeAfter(at(2024, 1, 1, 0, 0, 0));
        assertEquals(2024, result.getYear());
        assertEquals(1, result.getMonthValue());
        assertEquals(java.time.DayOfWeek.FRIDAY, result.getDayOfWeek());
        assertTrue(result.getDayOfMonth() > 24, "should be the last friday, got " + result.getDayOfMonth());
    }

    @Test
    public void nthDayOfWeekThirdFriday() {
        CronExpression cron = new CronExpression("0 0 0 ? * 6#3");
        ZonedDateTime result = cron.nextValidTimeAfter(at(2024, 1, 1, 0, 0, 0));
        assertEquals(java.time.DayOfWeek.FRIDAY, result.getDayOfWeek());
        int occurrence = ((result.getDayOfMonth() - 1) / 7) + 1;
        assertEquals(3, occurrence);
    }

    @Test
    public void nearestWeekdayToFifteenth() {
        CronExpression cron = new CronExpression("0 0 0 15W * ?");
        ZonedDateTime result = cron.nextValidTimeAfter(at(2024, 6, 1, 0, 0, 0));
        assertTrue(result.getDayOfWeek() != java.time.DayOfWeek.SATURDAY && result.getDayOfWeek() != java.time.DayOfWeek.SUNDAY);
        assertTrue(Math.abs(result.getDayOfMonth() - 15) <= 2);
    }

    @Test
    public void stepsAndRangesInSeconds() {
        CronExpression cron = new CronExpression("0/20 * * * * ?");
        ZonedDateTime start = at(2024, 1, 1, 0, 0, 5);
        assertEquals(at(2024, 1, 1, 0, 0, 20), cron.nextValidTimeAfter(start));
        assertEquals(at(2024, 1, 1, 0, 0, 40), cron.nextValidTimeAfter(at(2024, 1, 1, 0, 0, 20)));
        assertEquals(at(2024, 1, 1, 0, 1, 0), cron.nextValidTimeAfter(at(2024, 1, 1, 0, 0, 40)));
    }

    @Test
    public void yearFieldRestrictsMatches() {
        CronExpression cron = new CronExpression("0 0 0 1 1 ? 2030");
        ZonedDateTime result = cron.nextValidTimeAfter(at(2024, 1, 1, 0, 0, 0));
        assertEquals(2030, result.getYear());
    }

    @Test
    public void matchesReflectsExactCronTimes() {
        CronExpression cron = new CronExpression("0 0 * * * ?");
        assertTrue(cron.matches(at(2024, 5, 5, 10, 0, 0)));
        assertTrue(!cron.matches(at(2024, 5, 5, 10, 1, 0)));
    }

    @Test
    public void intervalScheduleParsesUnits() {
        assertEquals(Duration.ofHours(1), IntervalSchedule.parse("1h").duration());
        assertEquals(Duration.ofMinutes(30), IntervalSchedule.parse("30m").duration());
        assertEquals(Duration.ofSeconds(45), IntervalSchedule.parse("45s").duration());
        assertEquals(Duration.ofDays(2), IntervalSchedule.parse("2d").duration());
        ZonedDateTime start = at(2024, 1, 1, 0, 0, 0);
        IntervalSchedule schedule = IntervalSchedule.parse("15m");
        assertEquals(at(2024, 1, 1, 0, 15, 0), schedule.nextValidTimeAfter(start));
    }
}
