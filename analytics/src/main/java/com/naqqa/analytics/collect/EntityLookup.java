package com.naqqa.analytics.collect;

import com.naqqa.analytics.spi.AnalyticsEntityResolver;
import com.naqqa.analytics.spi.AnalyticsEntityResolver.EntityInfo;
import lombok.extern.slf4j.Slf4j;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

@Slf4j
public class EntityLookup {

    private static final EntityInfo MISSING = new EntityInfo(null, null, null, false);

    private final AnalyticsEntityResolver resolver;
    private final List<Pattern> testPatterns;
    private final long ttlMs;
    private final int maxEntries;
    private final Clock clock;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    public EntityLookup(AnalyticsEntityResolver resolver, List<String> testTitlePatterns, long ttlMs, int maxEntries, Clock clock) {
        this.resolver = resolver == null ? AnalyticsEntityResolver.NONE : resolver;
        List<Pattern> patterns = new ArrayList<>();
        if (testTitlePatterns != null) {
            for (String p : testTitlePatterns) {
                if (p != null && !p.isBlank()) {
                    patterns.add(Pattern.compile(p));
                }
            }
        }
        this.testPatterns = List.copyOf(patterns);
        this.ttlMs = ttlMs;
        this.maxEntries = maxEntries;
        this.clock = clock;
    }

    public boolean isTestTitle(String title) {
        if (title == null || title.isBlank()) {
            return false;
        }
        for (Pattern p : testPatterns) {
            if (p.matcher(title).find()) {
                return true;
            }
        }
        return false;
    }

    public EntityInfo find(String entityType, String entityId) {
        if (entityType == null || entityId == null) {
            return null;
        }
        String key = entityType + ":" + entityId;
        long now = clock.millis();
        Cached c = cache.get(key);
        if (c != null && c.expiresAt > now) {
            return c.info == MISSING ? null : c.info;
        }
        EntityInfo info;
        try {
            info = resolver.resolve(entityType, entityId);
        } catch (RuntimeException e) {
            log.debug("[analytics] entity resolve failed {}: {}", key, e.getMessage());
            return c == null || c.info == MISSING ? null : c.info;
        }
        info = normalize(info);
        put(key, info, now);
        return info;
    }

    public Map<String, EntityInfo> findAll(String entityType, Collection<String> ids) {
        Map<String, EntityInfo> out = new LinkedHashMap<>();
        if (entityType == null || ids == null || ids.isEmpty()) {
            return out;
        }
        long now = clock.millis();
        List<String> missing = new ArrayList<>();
        for (String id : ids) {
            Cached c = cache.get(entityType + ":" + id);
            if (c != null && c.expiresAt > now) {
                if (c.info != MISSING) {
                    out.put(id, c.info);
                }
            } else {
                missing.add(id);
            }
        }
        if (!missing.isEmpty()) {
            Map<String, EntityInfo> loaded;
            try {
                loaded = resolver.resolveAll(entityType, missing);
            } catch (RuntimeException e) {
                loaded = Map.of();
            }
            for (String id : missing) {
                EntityInfo info = normalize(loaded == null ? null : loaded.get(id));
                put(entityType + ":" + id, info, now);
                if (info != null) {
                    out.put(id, info);
                }
            }
        }
        return out;
    }

    private EntityInfo normalize(EntityInfo info) {
        if (info == null) {
            return null;
        }
        boolean test = info.test() || isTestTitle(info.title());
        return test == info.test() ? info : new EntityInfo(info.companyId(), info.categoryId(), info.title(), true);
    }

    private void put(String key, EntityInfo info, long now) {
        if (cache.size() >= maxEntries) {
            cache.entrySet().removeIf(e -> e.getValue().expiresAt <= now);
            if (cache.size() >= maxEntries) {
                cache.clear();
            }
        }
        cache.put(key, new Cached(info == null ? MISSING : info, now + ttlMs));
    }

    private record Cached(EntityInfo info, long expiresAt) {
    }
}
