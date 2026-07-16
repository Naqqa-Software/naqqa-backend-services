package com.naqqa.outreach.service;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/** Fixed GMT+3 working-window helpers (no DST), matching isWorkingDay()/isWorkingHours() in the script. */
public final class OutreachTime {

    private static final ZoneOffset GMT3 = ZoneOffset.ofHours(3);
    private static final DateTimeFormatter DMY = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private OutreachTime() {
    }

    public static boolean isWorkingDay() {
        int dow = OffsetDateTime.now(GMT3).getDayOfWeek().getValue(); // 1=Mon..7=Sun
        return dow >= 1 && dow <= 5;
    }

    public static boolean isWorkingHours(int startHour, int endHour) {
        int minutes = OffsetDateTime.now(GMT3).getHour() * 60 + OffsetDateTime.now(GMT3).getMinute();
        return minutes >= startHour * 60 && minutes < endHour * 60;
    }

    public static String todayKey() {
        return LocalDate.now(GMT3).format(DMY);
    }

    /** Working days (Mon–Fri) inclusive between two dates. */
    public static int workingDaysBetween(LocalDate start, LocalDate end) {
        if (start == null || end == null || end.isBefore(start)) {
            return 0;
        }
        int count = 0;
        LocalDate d = start;
        while (!d.isAfter(end)) {
            int dow = d.getDayOfWeek().getValue();
            if (dow >= 1 && dow <= 5) {
                count++;
            }
            d = d.plusDays(1);
        }
        return count;
    }
}
