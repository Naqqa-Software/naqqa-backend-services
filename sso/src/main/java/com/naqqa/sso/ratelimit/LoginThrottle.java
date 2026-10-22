package com.naqqa.sso.ratelimit;

import com.naqqa.sso.util.Bytes;
import com.naqqa.sso.util.Hashes;

import java.time.Clock;
import java.util.Locale;

public final class LoginThrottle {
    private final RateLimitStore store;
    private final Clock clock;
    private final ThrottleConfig config;

    public LoginThrottle(RateLimitStore store, Clock clock, ThrottleConfig config) {
        this.store = store;
        this.clock = clock;
        this.config = config;
    }

    public LoginThrottle(RateLimitStore store, Clock clock) {
        this(store, clock, ThrottleConfig.defaults());
    }

    private static String h(String v) {
        return Bytes.hex(Hashes.sha256(Bytes.utf8(v == null ? "" : v.trim().toLowerCase(Locale.ROOT)))).substring(0, 32);
    }

    private String accountKey(String account) {
        return "lt:a:" + h(account);
    }

    private String ipKey(String ip) {
        return "lt:i:" + h(ip);
    }

    private String pairKey(String account, String ip) {
        return "lt:p:" + h(account) + ":" + h(ip);
    }

    private int liveCount(String key, long now) {
        FailureState st = store.failures(key);
        if (st == null) {
            return 0;
        }
        if (now - st.lastFailureMillis() >= config.decay().toMillis()) {
            return 0;
        }
        return st.count();
    }

    private long delayFor(String key, long now) {
        FailureState st = store.failures(key);
        int count = liveCount(key, now);
        int over = count - config.freeAttempts();
        if (over <= 0 || st == null) {
            return 0;
        }
        long base = config.baseDelay().toMillis();
        long max = config.maxDelay().toMillis();
        long delay = base;
        for (int i = 1; i < over && delay < max; i++) {
            delay = Math.min(max, delay * 2);
        }
        delay = Math.min(delay, max);
        return Math.max(0, st.lastFailureMillis() + delay - now);
    }

    public Decision check(String account, String ip, boolean knownDevice) {
        return store.atomically(() -> {
            long now = clock.millis();
            String pk = pairKey(account, ip);
            long delay = delayFor(pk, now);
            boolean challenge = liveCount(pk, now) >= config.pairChallengeThreshold();
            if (!knownDevice) {
                delay = Math.max(delay, delayFor(ipKey(ip), now));
                challenge = challenge
                        || liveCount(accountKey(account), now) >= config.accountChallengeThreshold()
                        || liveCount(ipKey(ip), now) >= config.ipChallengeThreshold();
            }
            if (challenge) {
                return Decision.challenge();
            }
            if (delay > 0) {
                return Decision.delay(delay);
            }
            return Decision.allow();
        });
    }

    public Decision check(String account, String ip) {
        return check(account, ip, false);
    }

    public void recordFailure(String account, String ip) {
        store.atomically(() -> {
            long now = clock.millis();
            for (String key : new String[]{accountKey(account), ipKey(ip), pairKey(account, ip)}) {
                store.putFailures(key, new FailureState(liveCount(key, now) + 1, now));
            }
            return null;
        });
    }

    public void recordSuccess(String account, String ip) {
        store.atomically(() -> {
            store.remove(pairKey(account, ip));
            store.remove(accountKey(account));
            return null;
        });
    }
}
