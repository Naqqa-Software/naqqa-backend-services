package com.naqqa.sso.mfa;

import com.naqqa.sso.SsoException;
import com.naqqa.sso.util.Bytes;
import com.naqqa.sso.util.Hashes;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

public final class RecoveryCodes {
    public enum Outcome {
        OK, INVALID, THROTTLED, NOT_CONFIGURED
    }

    public static final String ALPHABET = "23456789ABCDEFGHJKMNPQRSTVWXYZ";
    public static final int CODE_COUNT = 10;
    public static final int CODE_LENGTH = 10;
    static final int LOOKUP_LENGTH = 16;

    private final Clock clock;
    private final RecoveryStore store;
    private final byte[] pepper;
    private final byte[] dummySalt = Bytes.random(16);
    private final int iterations;
    private final int maxAttempts;
    private final Duration cooldown;
    private final ConcurrentHashMap<String, AccountLock> locks = new ConcurrentHashMap<>();

    private static final class AccountLock {
        final ReentrantLock lock = new ReentrantLock();
        int holders;
    }

    public RecoveryCodes(Clock clock, RecoveryStore store, byte[] pepper, int iterations, int maxAttempts, Duration cooldown) {
        if (iterations < 1 || maxAttempts < 1 || pepper == null || pepper.length < 16) {
            throw new SsoException("recovery_config_invalid");
        }
        this.clock = clock;
        this.store = store;
        this.pepper = pepper.clone();
        this.iterations = iterations;
        this.maxAttempts = maxAttempts;
        this.cooldown = cooldown;
    }

    public RecoveryCodes(Clock clock, RecoveryStore store, byte[] pepper) {
        this(clock, store, pepper, 100_000, 5, Duration.ofMinutes(15));
    }

    private <T> T withAccount(String userId, Supplier<T> action) {
        String key = userId == null ? "" : userId;
        AccountLock l = locks.compute(key, (k, v) -> {
            AccountLock a = v == null ? new AccountLock() : v;
            a.holders++;
            return a;
        });
        l.lock.lock();
        try {
            return action.get();
        } finally {
            l.lock.unlock();
            locks.computeIfPresent(key, (k, v) -> --v.holders == 0 ? null : v);
        }
    }

    private byte[] hash(String normalized, byte[] salt) {
        try {
            PBEKeySpec spec = new PBEKeySpec(normalized.toCharArray(), salt, iterations, 256);
            byte[] out = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
            spec.clearPassword();
            return out;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private byte[] lookupId(String normalized) {
        return Arrays.copyOf(Hashes.hmacSha256(pepper, Bytes.utf8(normalized)), LOOKUP_LENGTH);
    }

    static String normalize(String input) {
        if (input == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (char c : input.toCharArray()) {
            if (c != '-' && !Character.isWhitespace(c)) {
                sb.append(Character.toUpperCase(c));
            }
        }
        return sb.toString().toUpperCase(Locale.ROOT);
    }

    private static String randomCode() {
        StringBuilder sb = new StringBuilder(CODE_LENGTH);
        int limit = 256 - (256 % ALPHABET.length());
        while (sb.length() < CODE_LENGTH) {
            byte[] r = Bytes.random(CODE_LENGTH * 2);
            for (byte b : r) {
                int v = b & 0xFF;
                if (v < limit && sb.length() < CODE_LENGTH) {
                    sb.append(ALPHABET.charAt(v % ALPHABET.length()));
                }
            }
        }
        return sb.toString();
    }

    public static String format(String code) {
        return code.substring(0, 5) + "-" + code.substring(5);
    }

    public List<String> generate(String userId) {
        return withAccount(userId, () -> generateLocked(userId));
    }

    private List<String> generateLocked(String userId) {
        List<String> shown = new ArrayList<>(CODE_COUNT);
        List<RecoverySet.Entry> entries = new ArrayList<>(CODE_COUNT);
        for (int i = 0; i < CODE_COUNT; i++) {
            String code = randomCode();
            byte[] salt = Bytes.random(16);
            entries.add(new RecoverySet.Entry(salt, hash(code, salt), false, lookupId(code)));
            shown.add(format(code));
        }
        store.save(new RecoverySet(userId, List.copyOf(entries), 0, null));
        return shown;
    }

    public Outcome redeem(String userId, String input) {
        return withAccount(userId, () -> redeemLocked(userId, input));
    }

    private Outcome redeemLocked(String userId, String input) {
        RecoverySet set = store.find(userId).orElse(null);
        if (set == null) {
            return Outcome.NOT_CONFIGURED;
        }
        Instant now = clock.instant();
        int failed = set.failedAttempts();
        if (failed >= maxAttempts && set.lastFailure() != null) {
            if (now.isBefore(set.lastFailure().plus(cooldown))) {
                return Outcome.THROTTLED;
            }
            failed = 0;
        }
        String normalized = normalize(input);
        boolean shapeOk = normalized.length() == CODE_LENGTH && normalized.chars().allMatch(c -> ALPHABET.indexOf(c) >= 0);
        int matchIndex = -1;
        List<RecoverySet.Entry> entries = set.entries();
        if (!shapeOk) {
            hash("0".repeat(CODE_LENGTH), dummySalt);
        } else {
            byte[] lookup = lookupId(normalized);
            int candidateIndex = -1;
            for (int i = 0; i < entries.size(); i++) {
                byte[] id = entries.get(i).lookupId();
                if (id != null && Bytes.constantTimeEquals(id, lookup) && candidateIndex < 0) {
                    candidateIndex = i;
                }
            }
            if (candidateIndex >= 0) {
                RecoverySet.Entry e = entries.get(candidateIndex);
                if (Bytes.constantTimeEquals(hash(normalized, e.salt()), e.hash()) && !e.used()) {
                    matchIndex = candidateIndex;
                }
            } else {
                boolean legacy = false;
                for (int i = 0; i < entries.size(); i++) {
                    RecoverySet.Entry e = entries.get(i);
                    if (e.lookupId() == null) {
                        legacy = true;
                        if (Bytes.constantTimeEquals(hash(normalized, e.salt()), e.hash()) && !e.used() && matchIndex < 0) {
                            matchIndex = i;
                        }
                    }
                }
                if (!legacy) {
                    hash(normalized, dummySalt);
                }
            }
        }
        if (matchIndex < 0) {
            store.recordFailure(userId, failed + 1, now);
            return Outcome.INVALID;
        }
        return store.markUsed(userId, entries.get(matchIndex).hash()) ? Outcome.OK : Outcome.INVALID;
    }

    public int remaining(String userId) {
        return store.find(userId).map(s -> (int) s.entries().stream().filter(e -> !e.used()).count()).orElse(0);
    }

    public void revokeAll(String userId) {
        withAccount(userId, () -> {
            store.delete(userId);
            return null;
        });
    }
}
