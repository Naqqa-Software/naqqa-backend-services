package com.naqqa.analytics.collect;

import com.naqqa.analytics.model.AnalyticsEvent;
import com.naqqa.analytics.model.AnalyticsSession;

import java.util.Collection;
import java.util.List;
import java.util.Map;

public interface AnalyticsStore {

    void insertEvents(List<AnalyticsEvent> events);

    void saveSessions(Collection<AnalyticsSession> sessions);

    AnalyticsSession latestSession(String clientSid);

    boolean markVisitor(String vid, long ts);

    void incrementQuality(String day, Map<String, Long> deltas);
}
