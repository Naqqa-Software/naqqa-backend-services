package com.naqqa.analytics.collect;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

@Slf4j
public class RedisKeyValueStore implements KeyValueStore {

    private final Supplier<StringRedisTemplate> template;
    private final KeyValueStore fallback;
    private volatile long failingUntil;

    public RedisKeyValueStore(Supplier<StringRedisTemplate> template, KeyValueStore fallback) {
        this.template = template;
        this.fallback = fallback;
    }

    private StringRedisTemplate redis() {
        if (System.currentTimeMillis() < failingUntil) {
            return null;
        }
        try {
            return template.get();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private void failed(RuntimeException e) {
        failingUntil = System.currentTimeMillis() + 30_000L;
        log.warn("[analytics] redis unavailable, using in-memory fallback for 30s: {}", e.getMessage());
    }

    @Override
    public long increment(String key, long delta, Duration ttl) {
        StringRedisTemplate r = redis();
        if (r != null) {
            try {
                Long v = r.opsForValue().increment(key, delta);
                if (v != null && v == delta) {
                    r.expire(key, ttl);
                }
                return v == null ? 0 : v;
            } catch (RuntimeException e) {
                failed(e);
            }
        }
        return fallback.increment(key, delta, ttl);
    }

    @Override
    public String getOrSet(String key, String value, Duration ttl) {
        StringRedisTemplate r = redis();
        if (r != null) {
            try {
                Boolean set = r.opsForValue().setIfAbsent(key, value, ttl);
                if (Boolean.TRUE.equals(set)) {
                    return value;
                }
                String existing = r.opsForValue().get(key);
                if (existing != null) {
                    return existing;
                }
            } catch (RuntimeException e) {
                failed(e);
            }
        }
        return fallback.getOrSet(key, value, ttl);
    }

    @Override
    public void hllAdd(String key, Duration ttl, String... values) {
        StringRedisTemplate r = redis();
        if (r != null) {
            try {
                Long added = r.opsForHyperLogLog().add(key, values);
                if (added != null) {
                    Long current = r.getExpire(key);
                    if (current == null || current < 0) {
                        r.expire(key, ttl);
                    }
                }
                return;
            } catch (RuntimeException e) {
                failed(e);
            }
        }
        fallback.hllAdd(key, ttl, values);
    }

    @Override
    public long hllCount(String key) {
        StringRedisTemplate r = redis();
        if (r != null) {
            try {
                Long v = r.opsForHyperLogLog().size(key);
                return v == null ? 0 : v;
            } catch (RuntimeException e) {
                failed(e);
            }
        }
        return fallback.hllCount(key);
    }

    @Override
    public long push(String key, List<String> values, long maxLength) {
        StringRedisTemplate r = redis();
        if (r != null) {
            try {
                Long size = r.opsForList().size(key);
                long room = maxLength - (size == null ? 0 : size);
                if (room <= 0) {
                    return size == null ? 0 : size;
                }
                List<String> slice = values.size() > room ? values.subList(0, (int) room) : values;
                Long after = r.opsForList().rightPushAll(key, slice);
                return after == null ? 0 : after;
            } catch (RuntimeException e) {
                failed(e);
            }
        }
        return fallback.push(key, values, maxLength);
    }

    @Override
    public List<String> pop(String key, int count) {
        List<String> out = new ArrayList<>(fallback.pop(key, count));
        StringRedisTemplate r = redis();
        if (r != null && out.size() < count) {
            try {
                List<String> v = r.opsForList().leftPop(key, count - out.size());
                if (v != null) {
                    out.addAll(v);
                }
            } catch (RuntimeException e) {
                failed(e);
            }
        }
        return out;
    }

    @Override
    public long length(String key) {
        long local = fallback.length(key);
        StringRedisTemplate r = redis();
        if (r != null) {
            try {
                Long v = r.opsForList().size(key);
                return local + (v == null ? 0 : v);
            } catch (RuntimeException e) {
                failed(e);
            }
        }
        return local;
    }

    @Override
    public boolean distributed() {
        return redis() != null;
    }
}
