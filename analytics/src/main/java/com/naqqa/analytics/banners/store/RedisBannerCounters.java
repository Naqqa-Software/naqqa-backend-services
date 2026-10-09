package com.naqqa.analytics.banners.store;

import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

@Slf4j
public class RedisBannerCounters implements BannerCounters {

    private final StringRedisTemplate redis;
    private final String prefix;
    private final BannerCounters fallback;
    private volatile long downUntil;

    public RedisBannerCounters(StringRedisTemplate redis, String prefix, BannerCounters fallback) {
        this.redis = redis;
        this.prefix = prefix == null ? "naqqa:an:bn:" : prefix;
        this.fallback = fallback;
    }

    @Override
    public int servedToday(String vid, String campaignId, LocalDate day) {
        if (vid == null) {
            return 0;
        }
        if (down()) {
            return fallback.servedToday(vid, campaignId, day);
        }
        try {
            String v = redis.opsForValue().get(prefix + MemoryBannerCounters.freqKey(vid, campaignId, day));
            return v == null ? 0 : Integer.parseInt(v);
        } catch (Exception e) {
            fail(e);
            return fallback.servedToday(vid, campaignId, day);
        }
    }

    @Override
    public void recordServe(String vid, String campaignId, LocalDate day) {
        if (vid == null) {
            return;
        }
        if (down()) {
            fallback.recordServe(vid, campaignId, day);
            return;
        }
        try {
            String key = prefix + MemoryBannerCounters.freqKey(vid, campaignId, day);
            Long n = redis.opsForValue().increment(key);
            if (n != null && n == 1L) {
                redis.expire(key, Duration.ofHours(48));
            }
            redis.opsForValue().set(prefix + MemoryBannerCounters.seenKey(vid, campaignId), "1", Duration.ofHours(24));
        } catch (Exception e) {
            fail(e);
            fallback.recordServe(vid, campaignId, day);
        }
    }

    @Override
    public boolean seen(String vid, String campaignId) {
        if (vid == null) {
            return false;
        }
        if (down()) {
            return fallback.seen(vid, campaignId);
        }
        try {
            return Boolean.TRUE.equals(redis.hasKey(prefix + MemoryBannerCounters.seenKey(vid, campaignId)));
        } catch (Exception e) {
            fail(e);
            return fallback.seen(vid, campaignId);
        }
    }

    @Override
    public boolean firstClick(String key, Duration window) {
        if (down()) {
            return fallback.firstClick(key, window);
        }
        try {
            Boolean ok = redis.opsForValue().setIfAbsent(prefix + "c:" + key, "1", window);
            return Boolean.TRUE.equals(ok);
        } catch (Exception e) {
            fail(e);
            return fallback.firstClick(key, window);
        }
    }

    @Override
    public Map<String, Long> rotation(String slot, LocalDate day) {
        if (down()) {
            return fallback.rotation(slot, day);
        }
        try {
            Map<Object, Object> raw = redis.opsForHash().entries(prefix + MemoryBannerCounters.rotationKey(slot, day));
            Map<String, Long> out = new HashMap<>(raw.size() * 2);
            raw.forEach((k, v) -> {
                try {
                    out.put(String.valueOf(k), Long.parseLong(String.valueOf(v)));
                } catch (NumberFormatException ignored) {
                    out.put(String.valueOf(k), 0L);
                }
            });
            return out;
        } catch (Exception e) {
            fail(e);
            return fallback.rotation(slot, day);
        }
    }

    @Override
    public void recordRotation(String slot, LocalDate day, Map<String, Long> steps) {
        if (slot == null || day == null || steps == null || steps.isEmpty()) {
            return;
        }
        if (down()) {
            fallback.recordRotation(slot, day, steps);
            return;
        }
        String key = prefix + MemoryBannerCounters.rotationKey(slot, day);
        try {
            redis.executePipelined(new SessionCallback<Object>() {
                @Override
                @SuppressWarnings({"unchecked", "rawtypes"})
                public Object execute(RedisOperations operations) throws DataAccessException {
                    for (Map.Entry<String, Long> e : steps.entrySet()) {
                        if (e.getKey() != null && e.getValue() != null && e.getValue() > 0) {
                            operations.opsForHash().increment(key, e.getKey(), e.getValue());
                        }
                    }
                    operations.expire(key, Duration.ofHours(48));
                    return null;
                }
            });
        } catch (Exception e) {
            fail(e);
            fallback.recordRotation(slot, day, steps);
        }
    }

    private boolean down() {
        return System.currentTimeMillis() < downUntil;
    }

    private void fail(Exception e) {
        downUntil = System.currentTimeMillis() + 30_000L;
        log.warn("Banner counters: Redis unavailable, using memory fallback for 30s: {}", e.getMessage());
    }
}
