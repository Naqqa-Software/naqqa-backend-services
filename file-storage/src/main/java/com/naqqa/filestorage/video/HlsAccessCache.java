package com.naqqa.filestorage.video;

import com.naqqa.filestorage.config.FileStorageProperties;
import org.springframework.security.core.Authentication;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class HlsAccessCache {

    private record Key(String principal, String groupId) {}
    private record Entry(boolean allowed, long expiresAtMillis) {}

    private final HlsAccessPolicy policy;
    private final FileStorageProperties props;
    private final Map<Key, Entry> cache = new ConcurrentHashMap<>();

    public HlsAccessCache(HlsAccessPolicy policy, FileStorageProperties props) {
        this.policy = policy;
        this.props = props;
    }

    public boolean canStream(String groupId, Authentication authentication) {
        long ttlSeconds = props.getHls().getAccessCacheTtlSeconds();
        if (ttlSeconds <= 0) {
            return policy.canStream(groupId, authentication);
        }

        Key key = new Key(authentication.getName(), groupId);
        long now = System.currentTimeMillis();

        Entry hit = cache.get(key);
        if (hit != null && hit.expiresAtMillis() > now) {
            return hit.allowed();
        }

        boolean allowed = policy.canStream(groupId, authentication);

        int maxEntries = props.getHls().getAccessCacheMaxEntries();
        if (cache.size() > maxEntries) {
            cache.entrySet().removeIf(e -> e.getValue().expiresAtMillis() <= now);
            if (cache.size() > maxEntries) {
                cache.clear();
            }
        }
        cache.put(key, new Entry(allowed, now + ttlSeconds * 1000L));
        return allowed;
    }

    public void invalidate(String groupId, String principal) {
        cache.remove(new Key(principal, groupId));
    }
}
