package com.naqqa.chatbot.service;

import com.naqqa.chatbot.entities.ChatSettingsEntity;

import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

public final class ChatSchedule {

    private ChatSchedule() {
    }

    public static ZoneId zone(ChatSettingsEntity settings) {
        try {
            return ZoneId.of(settings.getTimezone());
        } catch (Exception e) {
            return ZoneId.of("Europe/Chisinau");
        }
    }

    public static boolean isWithinSchedule(ChatSettingsEntity settings, ZonedDateTime now) {
        List<ChatSettingsEntity.ScheduleSlot> slots = settings.getOperatorSchedule();
        if (slots == null || slots.isEmpty()) {
            return true;
        }
        ZonedDateTime local = now.withZoneSameInstant(zone(settings));
        int day = local.getDayOfWeek().getValue();
        LocalTime time = local.toLocalTime();
        for (ChatSettingsEntity.ScheduleSlot slot : slots) {
            if (slot == null || slot.getDayOfWeek() != day) {
                continue;
            }
            LocalTime from = parse(slot.getFrom());
            LocalTime to = parse(slot.getTo());
            if (from != null && to != null && !time.isBefore(from) && time.isBefore(to)) {
                return true;
            }
        }
        return false;
    }

    public static String describe(ChatSettingsEntity settings, List<String> dayNames) {
        List<ChatSettingsEntity.ScheduleSlot> slots = settings.getOperatorSchedule();
        if (slots == null || slots.isEmpty()) {
            return "";
        }
        String[] hours = new String[8];
        for (ChatSettingsEntity.ScheduleSlot slot : slots) {
            if (slot != null && slot.getDayOfWeek() >= 1 && slot.getDayOfWeek() <= 7) {
                hours[slot.getDayOfWeek()] = slot.getFrom() + "–" + slot.getTo();
            }
        }
        List<String> groups = new java.util.ArrayList<>();
        int day = 1;
        while (day <= 7) {
            if (hours[day] == null) {
                day++;
                continue;
            }
            int end = day;
            while (end < 7 && hours[day].equals(hours[end + 1])) {
                end++;
            }
            String range = end == day ? name(dayNames, day) : name(dayNames, day) + "–" + name(dayNames, end);
            groups.add(range + " " + hours[day]);
            day = end + 1;
        }
        return String.join(", ", groups);
    }

    private static String name(List<String> dayNames, int day) {
        return dayNames != null && dayNames.size() >= day ? dayNames.get(day - 1) : String.valueOf(day);
    }

    public static LocalTime parse(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalTime.parse(value.trim());
        } catch (Exception e) {
            return null;
        }
    }
}
