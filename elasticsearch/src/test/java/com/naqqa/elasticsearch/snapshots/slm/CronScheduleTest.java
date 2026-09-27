package com.naqqa.elasticsearch.snapshots.slm;

import com.naqqa.elasticsearch.test.Test;

import java.time.Instant;

import static com.naqqa.elasticsearch.test.Assert.assertEquals;

public final class CronScheduleTest {

    @Test
    public void dailyAtMidnightFiresNextDay() {
        CronSchedule schedule = new CronSchedule("0 0 * * *");
        Instant after = Instant.parse("2026-01-01T13:45:00Z");
        Instant next = schedule.nextFireTime(after);
        assertEquals(Instant.parse("2026-01-02T00:00:00Z"), next);
    }

    @Test
    public void hourlyAtMinuteThirtyFiresWithinTheHour() {
        CronSchedule schedule = new CronSchedule("30 * * * *");
        Instant after = Instant.parse("2026-01-01T13:10:00Z");
        Instant next = schedule.nextFireTime(after);
        assertEquals(Instant.parse("2026-01-01T13:30:00Z"), next);
    }

    @Test
    public void everyFiveMinutesUsesStepSyntax() {
        CronSchedule schedule = new CronSchedule("*/5 * * * *");
        Instant after = Instant.parse("2026-01-01T00:02:00Z");
        Instant next = schedule.nextFireTime(after);
        assertEquals(Instant.parse("2026-01-01T00:05:00Z"), next);
    }

    @Test
    public void weekdayFieldRestrictsToMonday() {
        CronSchedule schedule = new CronSchedule("0 9 * * 1");
        Instant after = Instant.parse("2026-01-01T00:00:00Z");
        Instant next = schedule.nextFireTime(after);
        assertEquals("2026-01-05T09:00:00Z", next.toString());
    }
}
