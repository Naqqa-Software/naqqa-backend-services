package com.naqqa.filestorage.video;

import com.naqqa.filestorage.config.FileStorageProperties;
import org.springframework.security.core.Authentication;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Short-lived memo of an {@link HlsAccessPolicy} decision.
 *
 * <p>An HLS playthrough is hundreds of segment requests, each of which has to be authorised — that
 * is the cost of serving through the application instead of a CDN. Answering every one from the
 * application's policy (usually a database query) would turn a 30-minute video into ~300 identical
 * lookups. Memoising collapses that to one, which is what makes CDN-less serving affordable.
 *
 * <p>Deliberately a plain map rather than a cache library: this needs no eviction policy beyond TTL
 * and the library should not impose a cache dependency on its consumers. Entries are bounded by
 * {@code viewers x ladders} and swept once the map exceeds the configured maximum.
 *
 * <p><b>Revocation lag is intentional and bounded</b> by the TTL — the same trade a CDN signed
 * cookie makes, at a far shorter horizon. Set the TTL to zero to check every request.
 */
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
            // Sweeping only expired entries is not a bound: under a burst of distinct viewers
            // nothing has expired yet and the map keeps growing. Clearing is crude but correct —
            // the cost of a miss is one policy call, and the alternative is unbounded heap.
            if (cache.size() > maxEntries) {
                cache.clear();
            }
        }
        cache.put(key, new Entry(allowed, now + ttlSeconds * 1000L));
        return allowed;
    }

    /** Drops a memo so a revoked grant takes effect immediately instead of at TTL expiry. */
    public void invalidate(String groupId, String principal) {
        cache.remove(new Key(principal, groupId));
    }
}
