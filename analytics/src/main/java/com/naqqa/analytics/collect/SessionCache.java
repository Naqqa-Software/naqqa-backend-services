package com.naqqa.analytics.collect;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

public class SessionCache {

    private final Map<String, SessionState> cache;
    private final Function<String, SessionState> loader;
    private final Map<String, Boolean> visitors;
    private final Function<String, Boolean> visitorMarker;

    public SessionCache(int capacity, Function<String, SessionState> loader, Function<String, Boolean> visitorMarker) {
        int cap = Math.max(100, capacity);
        this.cache = new LinkedHashMap<>(256, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, SessionState> eldest) {
                return size() > cap;
            }
        };
        this.visitors = new LinkedHashMap<>(256, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                return size() > cap * 2;
            }
        };
        this.loader = loader;
        this.visitorMarker = visitorMarker;
    }

    public SessionState get(String clientSid) {
        if (clientSid == null) {
            return null;
        }
        synchronized (cache) {
            SessionState s = cache.get(clientSid);
            if (s != null) {
                return s;
            }
        }
        SessionState loaded = null;
        if (loader != null) {
            try {
                loaded = loader.apply(clientSid);
            } catch (RuntimeException e) {
                loaded = null;
            }
        }
        if (loaded != null) {
            synchronized (cache) {
                cache.putIfAbsent(clientSid, loaded);
                return cache.get(clientSid);
            }
        }
        return null;
    }

    public void put(String clientSid, SessionState state) {
        synchronized (cache) {
            cache.put(clientSid, state);
        }
    }

    public boolean newVisitor(String vid, long ts) {
        if (vid == null) {
            return false;
        }
        synchronized (visitors) {
            if (visitors.containsKey(vid)) {
                return false;
            }
        }
        boolean isNew;
        try {
            isNew = visitorMarker == null || Boolean.TRUE.equals(visitorMarker.apply(vid));
        } catch (RuntimeException e) {
            isNew = false;
        }
        synchronized (visitors) {
            visitors.put(vid, Boolean.TRUE);
        }
        return isNew;
    }
}
