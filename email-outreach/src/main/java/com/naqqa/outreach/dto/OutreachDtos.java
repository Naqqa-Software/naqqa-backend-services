package com.naqqa.outreach.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Client-facing DTOs for the outreach admin surface. */
public final class OutreachDtos {

    /** One row in the "Emails sent" table. */
    public record SentEmailRow(
            String id, String profileKey, String fromEmail, String companyName, String toEmail, String toName,
            String website, String city, String subject, String body, int sequenceStep,
            int followupCount, String responseText, String notes,
            String status, Instant sentAt, Instant repliedAt) {
    }

    /** Edit payload for a sent-email record. */
    public record SentEmailUpdate(
            String companyName, String toEmail, String website, String city,
            String subject, String body, String status, String notes, String responseText) {
    }

    /** Sender profile view (app password is never returned — write-only). */
    public record ProfileView(
            String key, String fromEmail, String fromName, String signature,
            LocalDate warmupStartDate, List<Integer> warmupSchedule, Integer dailyLimit,
            boolean enabled, boolean hasPassword) {
    }

    /** Upsert payload for a sender profile. */
    public record ProfileUpsert(
            String fromEmail, String fromName, String signature, String appPassword,
            LocalDate warmupStartDate, List<Integer> warmupSchedule, Integer dailyLimit, Boolean enabled) {
    }

    /** Global runtime switches shown on the emails page (sending + extraction on/off + pipeline counts). */
    public record SettingsView(boolean sendingActive, boolean extractionActive, long enrichedCount) {
    }

    /** Patch payload for the runtime switches — only the non-null field(s) are applied. */
    public record SettingsUpdate(Boolean sendingActive, Boolean extractionActive) {
    }

    /** A single {label, value} slice used by the analytics charts. */
    public record StatBucket(String label, long value) {
    }

    /** One point on the "emails over time" line (day = yyyy-MM-dd). */
    public record TimePoint(String day, long value) {
    }

    /** Aggregated sent-email stats for the analytics tab (respects the date/profile filters). */
    public record OutreachStats(
            long total, List<StatBucket> byStatus, List<StatBucket> byProfile,
            List<StatBucket> byStep, List<TimePoint> overTime) {
    }

    /**
     * Per-sender deliverability snapshot for the Emails tab — the live numbers the bounce throttle acts
     * on: 7-day sent/bounced + rate, the resulting {@code state} (continue|freeze|reduce|pause|stop) and
     * today's effective cap vs how many have gone out. Explains at a glance why a sender isn't sending.
     */
    public record ProfileDeliverability(
            String profileKey, String fromEmail, boolean enabled,
            long sent7d, long bounced7d, double bounceRatePct,
            String state, String reason, Instant pausedUntil, Instant recoversAt,
            int dailyCap, int sentToday) {
    }

    private OutreachDtos() {
    }
}
