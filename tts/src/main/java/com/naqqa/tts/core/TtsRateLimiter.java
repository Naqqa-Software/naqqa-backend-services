package com.naqqa.tts.core;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

public class TtsRateLimiter {

    private static final long WINDOW_MS = 60_000L;
    private static final int MAX_KEYS = 20_000;

    private record Window(long start, int count) {
    }

    private final int perMinute;
    private final LongSupplier clock;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    public TtsRateLimiter(int perMinute) {
        this(perMinute, System::currentTimeMillis);
    }

    public TtsRateLimiter(int perMinute, LongSupplier clock) {
        this.perMinute = perMinute;
        this.clock = clock;
    }

    public boolean allow(String key) {
        if (perMinute <= 0) {
            return true;
        }
        long now = clock.getAsLong();
        if (windows.size() > MAX_KEYS) {
            windows.entrySet().removeIf(e -> now - e.getValue().start() >= WINDOW_MS);
        }
        boolean[] allowed = {false};
        windows.compute(key == null ? "?" : key, (k, w) -> {
            if (w == null || now - w.start() >= WINDOW_MS) {
                allowed[0] = true;
                return new Window(now, 1);
            }
            if (w.count() >= perMinute) {
                return w;
            }
            allowed[0] = true;
            return new Window(w.start(), w.count() + 1);
        });
        return allowed[0];
    }
}
