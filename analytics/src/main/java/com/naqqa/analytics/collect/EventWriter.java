package com.naqqa.analytics.collect;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.naqqa.analytics.model.AnalyticsEvent;
import com.naqqa.analytics.model.AnalyticsSession;
import lombok.extern.slf4j.Slf4j;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
public class EventWriter {

    private final AnalyticsStore store;
    private final KeyValueStore backlog;
    private final String backlogKey;
    private final long backlogMax;
    private final int batchSize;
    private final QualityCounters quality;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final ZoneId zone;
    private final BlockingQueue<AnalyticsEvent> queue;
    private final Map<String, AnalyticsSession> sessions = new ConcurrentHashMap<>();
    private final AtomicBoolean flushing = new AtomicBoolean(false);
    private volatile boolean storeHealthy = true;

    public EventWriter(AnalyticsStore store, KeyValueStore backlog, String backlogKey, long backlogMax, int queueCapacity, int batchSize,
                       QualityCounters quality, ObjectMapper mapper, Clock clock, ZoneId zone) {
        this.store = store;
        this.backlog = backlog;
        this.backlogKey = backlogKey;
        this.backlogMax = backlogMax;
        this.batchSize = Math.max(1, batchSize);
        this.quality = quality;
        this.mapper = mapper;
        this.clock = clock;
        this.zone = zone;
        this.queue = new ArrayBlockingQueue<>(Math.max(100, queueCapacity));
    }

    public void offer(List<AnalyticsEvent> events) {
        List<AnalyticsEvent> overflow = new ArrayList<>();
        for (AnalyticsEvent e : events) {
            if (!queue.offer(e)) {
                overflow.add(e);
            }
        }
        if (!overflow.isEmpty()) {
            spill(overflow);
        }
    }

    public void session(AnalyticsSession session) {
        if (session != null && session.getSid() != null) {
            sessions.put(session.getSid(), session);
        }
    }

    public int queueSize() {
        return queue.size();
    }

    public long backlogSize() {
        try {
            return backlog.length(backlogKey);
        } catch (RuntimeException e) {
            return -1;
        }
    }

    public void flush() {
        if (!flushing.compareAndSet(false, true)) {
            return;
        }
        try {
            flushSessions();
            boolean ok = true;
            while (ok && !queue.isEmpty()) {
                List<AnalyticsEvent> batch = new ArrayList<>(batchSize);
                queue.drainTo(batch, batchSize);
                if (batch.isEmpty()) {
                    break;
                }
                ok = write(batch, true);
            }
            if (ok) {
                drainBacklog();
            }
            flushQuality();
        } finally {
            flushing.set(false);
        }
    }

    private boolean write(List<AnalyticsEvent> batch, boolean spillOnFailure) {
        try {
            long now = clock.millis();
            for (AnalyticsEvent e : batch) {
                if (e.getRcv() == null) {
                    e.setRcv(Instant.ofEpochMilli(now));
                }
            }
            store.insertEvents(batch);
            for (AnalyticsEvent e : batch) {
                quality.delay(now - e.epochMs());
            }
            quality.flushed(now);
            storeHealthy = true;
            return true;
        } catch (RuntimeException ex) {
            if (storeHealthy) {
                log.warn("[analytics] event store unavailable, spilling to backlog: {}", ex.getMessage());
            }
            storeHealthy = false;
            if (spillOnFailure) {
                spill(batch);
            }
            return false;
        }
    }

    private void drainBacklog() {
        for (int round = 0; round < 20; round++) {
            List<String> raw;
            try {
                raw = backlog.pop(backlogKey, batchSize);
            } catch (RuntimeException e) {
                return;
            }
            if (raw.isEmpty()) {
                return;
            }
            List<AnalyticsEvent> batch = new ArrayList<>(raw.size());
            for (String s : raw) {
                try {
                    batch.add(mapper.readValue(s, AnalyticsEvent.class));
                } catch (Exception e) {
                    quality.reject("backlog_corrupt", 1);
                }
            }
            if (!batch.isEmpty() && !write(batch, true)) {
                return;
            }
        }
    }

    private void spill(List<AnalyticsEvent> events) {
        List<String> serialized = new ArrayList<>(events.size());
        for (AnalyticsEvent e : events) {
            try {
                serialized.add(mapper.writeValueAsString(e));
            } catch (Exception ex) {
                quality.reject(QualityCounters.DROPPED, 1);
            }
        }
        long before;
        try {
            before = backlog.length(backlogKey);
            long after = backlog.push(backlogKey, serialized, backlogMax);
            long stored = Math.max(0, after - before);
            if (stored < serialized.size()) {
                quality.reject(QualityCounters.DROPPED, serialized.size() - stored);
            }
        } catch (RuntimeException ex) {
            quality.reject(QualityCounters.DROPPED, serialized.size());
        }
    }

    private void flushSessions() {
        if (sessions.isEmpty()) {
            return;
        }
        List<AnalyticsSession> batch = new ArrayList<>();
        for (String sid : new ArrayList<>(sessions.keySet())) {
            AnalyticsSession s = sessions.remove(sid);
            if (s != null) {
                batch.add(s);
            }
        }
        try {
            store.saveSessions(batch);
        } catch (RuntimeException e) {
            for (AnalyticsSession s : batch) {
                sessions.putIfAbsent(s.getSid(), s);
            }
        }
    }

    private void flushQuality() {
        Map<String, Long> deltas = quality.drain();
        if (deltas.isEmpty()) {
            return;
        }
        try {
            store.incrementQuality(LocalDate.ofInstant(Instant.ofEpochMilli(clock.millis()), zone).toString(), new LinkedHashMap<>(deltas));
        } catch (RuntimeException e) {
            quality.restore(deltas);
        }
    }
}
