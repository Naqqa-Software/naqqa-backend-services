package com.naqqa.auth.sso.store;

import com.naqqa.sso.SsoException;
import com.naqqa.sso.crypto.SecretBox;
import com.naqqa.sso.mfa.SecretSealer;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class PersistentSecretBox implements SecretSealer {

    public static final long DEFAULT_RESERVE = 1000;

    private final SecretBoxCounterStore store;
    private final long reserve;
    private final SecretBox box;
    private final Map<String, Long> limits = new ConcurrentHashMap<>();

    public PersistentSecretBox(SecretBoxCounterStore store, long reserve, String kid, byte[] key) {
        if (store == null || reserve < 1) {
            throw new SsoException("secretbox.persist_config_invalid");
        }
        this.store = store;
        this.reserve = reserve;
        String bootstrap = "boot" + Long.toHexString(System.nanoTime());
        this.box = new SecretBox(bootstrap, SecretBox.generateKey());
        addKey(kid, key);
        box.activate(kid);
        box.removeKey(bootstrap);
    }

    public PersistentSecretBox(SecretBoxCounterStore store, String kid, byte[] key) {
        this(store, DEFAULT_RESERVE, kid, key);
    }

    public final synchronized void addKey(String kid, byte[] key) {
        long total = store.reserve(kid, reserve);
        long previous = total - reserve;
        box.addKey(kid, key, previous);
        limits.put(kid, previous + reserve);
    }

    public synchronized void activate(String kid) {
        box.activate(kid);
    }

    public synchronized void removeKey(String kid) {
        store.recordUsed(kid, box.messagesSealed(kid));
        box.removeKey(kid);
        limits.remove(kid);
    }

    private synchronized void ensureCapacity(String kid) {
        long limit = limits.get(kid);
        while (box.messagesSealed(kid) + 1 > limit) {
            store.reserve(kid, reserve);
            limit += reserve;
            limits.put(kid, limit);
        }
    }

    @Override
    public byte[] seal(byte[] plaintext, byte[] aad) {
        String kid = box.activeKid();
        if (box.messagesSealed(kid) + 1 > limits.get(kid)) {
            ensureCapacity(kid);
        }
        return box.seal(plaintext, aad);
    }

    @Override
    public byte[] open(byte[] sealed, byte[] aad) {
        return box.open(sealed, aad);
    }

    public String activeKid() {
        return box.activeKid();
    }

    public long messagesSealed(String kid) {
        return box.messagesSealed(kid);
    }

    public boolean needsReseal(byte[] sealed) {
        return box.needsReseal(sealed);
    }

    public synchronized void close() {
        for (String kid : limits.keySet()) {
            store.recordUsed(kid, box.messagesSealed(kid));
        }
    }
}
