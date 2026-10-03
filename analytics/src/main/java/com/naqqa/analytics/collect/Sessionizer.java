package com.naqqa.analytics.collect;

import com.naqqa.analytics.collect.ChannelClassifier.Attribution;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

public final class Sessionizer {

    public static final String FIRST = "first";
    public static final String INACTIVITY = "inactivity";
    public static final String MIDNIGHT = "midnight";
    public static final String CAMPAIGN = "campaign";

    private final long inactivityMs;
    private final ZoneId zone;

    public Sessionizer(long inactivityMs, ZoneId zone) {
        this.inactivityMs = inactivityMs;
        this.zone = zone;
    }

    public String day(long ts) {
        return LocalDate.ofInstant(Instant.ofEpochMilli(ts), zone).toString();
    }

    public Decision decide(SessionState previous, long ts, Attribution incoming) {
        if (previous == null) {
            return new Decision(true, FIRST);
        }
        if (ts < previous.lastMs()) {
            return new Decision(false, null);
        }
        if (ts - previous.lastMs() > inactivityMs) {
            return new Decision(true, INACTIVITY);
        }
        if (!day(ts).equals(previous.day())) {
            return new Decision(true, MIDNIGHT);
        }
        if (incoming != null && incoming.triggersNewSession() && !incoming.key().equals(previous.campaignKey())) {
            return new Decision(true, CAMPAIGN);
        }
        return new Decision(false, null);
    }

    public static String nextSid(String clientSid, SessionState previous, long ts) {
        if (previous == null) {
            return clientSid;
        }
        return clientSid + "." + Long.toString(ts, 36);
    }

    public record Decision(boolean newSession, String reason) {
    }
}
