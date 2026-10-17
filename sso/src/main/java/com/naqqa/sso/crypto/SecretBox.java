package com.naqqa.sso.crypto;

import com.naqqa.sso.SsoException;
import com.naqqa.sso.util.Bytes;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class SecretBox {
    public static final long MAX_MESSAGES_PER_KEY = 1L << 32;
    public static final int KEY_LENGTH = 32;
    public static final int NONCE_LENGTH = 12;
    public static final int TAG_BITS = 128;
    public static final int MAX_KID_LENGTH = 32;

    private static final class Slot {
        private final SecretKeySpec key;
        private final AtomicLong sealed;

        private Slot(byte[] raw, long sealed) {
            this.key = new SecretKeySpec(raw, "AES");
            this.sealed = new AtomicLong(sealed);
        }
    }

    private final Map<String, Slot> keys = new ConcurrentHashMap<>();
    private volatile String activeKid;

    public SecretBox(String kid, byte[] key) {
        addKey(kid, key, 0);
        activeKid = kid;
    }

    public static byte[] generateKey() {
        return Bytes.random(KEY_LENGTH);
    }

    public static byte[] aad(String... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (String p : parts) {
            if (p == null) {
                throw new SsoException("secretbox.aad_required");
            }
            byte[] b = p.getBytes(StandardCharsets.UTF_8);
            out.write(b.length >>> 24);
            out.write(b.length >>> 16);
            out.write(b.length >>> 8);
            out.write(b.length);
            out.write(b, 0, b.length);
        }
        return out.toByteArray();
    }

    public synchronized void addKey(String kid, byte[] key, long messagesAlreadySealed) {
        validateKid(kid);
        if (key == null || key.length != KEY_LENGTH) {
            throw new SsoException("secretbox.key_length");
        }
        if (messagesAlreadySealed < 0 || messagesAlreadySealed > MAX_MESSAGES_PER_KEY) {
            throw new SsoException("secretbox.counter_invalid");
        }
        if (keys.containsKey(kid)) {
            throw new SsoException("secretbox.duplicate_kid");
        }
        keys.put(kid, new Slot(key.clone(), messagesAlreadySealed));
    }

    public synchronized void activate(String kid) {
        if (!keys.containsKey(kid)) {
            throw new SsoException("secretbox.unknown_kid");
        }
        activeKid = kid;
    }

    public synchronized void removeKey(String kid) {
        if (kid.equals(activeKid)) {
            throw new SsoException("secretbox.cannot_remove_active");
        }
        keys.remove(kid);
    }

    public String activeKid() {
        return activeKid;
    }

    public long messagesSealed(String kid) {
        Slot s = keys.get(kid);
        if (s == null) {
            throw new SsoException("secretbox.unknown_kid");
        }
        return s.sealed.get();
    }

    private static void validateKid(String kid) {
        if (kid == null || kid.isEmpty() || kid.length() > MAX_KID_LENGTH) {
            throw new SsoException("secretbox.kid_invalid");
        }
        for (int i = 0; i < kid.length(); i++) {
            char c = kid.charAt(i);
            boolean ok = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-' || c == '_';
            if (!ok) {
                throw new SsoException("secretbox.kid_invalid");
            }
        }
    }

    public byte[] seal(byte[] plaintext, byte[] aad) {
        if (plaintext == null) {
            throw new SsoException("secretbox.plaintext_required");
        }
        requireAad(aad);
        String kid = activeKid;
        Slot slot = keys.get(kid);
        long n = slot.sealed.incrementAndGet();
        if (n > MAX_MESSAGES_PER_KEY) {
            slot.sealed.set(MAX_MESSAGES_PER_KEY + 1);
            throw new SsoException("secretbox.key_exhausted");
        }
        byte[] kidBytes = kid.getBytes(StandardCharsets.US_ASCII);
        byte[] nonce = Bytes.random(NONCE_LENGTH);
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, slot.key, new GCMParameterSpec(TAG_BITS, nonce));
            byte[] header = Bytes.concat(new byte[]{(byte) kidBytes.length}, kidBytes);
            c.updateAAD(header);
            c.updateAAD(aad);
            byte[] ct = c.doFinal(plaintext);
            return Bytes.concat(header, nonce, ct);
        } catch (GeneralSecurityException e) {
            throw new SsoException("secretbox.seal_failed");
        }
    }

    public byte[] open(byte[] box, byte[] aad) {
        requireAad(aad);
        String kid = kidOf(box);
        Slot slot = keys.get(kid);
        if (slot == null) {
            throw new SsoException("secretbox.open_failed");
        }
        int off = 1 + kid.length();
        if (box.length < off + NONCE_LENGTH + TAG_BITS / 8) {
            throw new SsoException("secretbox.open_failed");
        }
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, slot.key, new GCMParameterSpec(TAG_BITS, Arrays.copyOfRange(box, off, off + NONCE_LENGTH)));
            c.updateAAD(Arrays.copyOfRange(box, 0, off));
            c.updateAAD(aad);
            return c.doFinal(box, off + NONCE_LENGTH, box.length - off - NONCE_LENGTH);
        } catch (GeneralSecurityException e) {
            throw new SsoException("secretbox.open_failed");
        }
    }

    public String sealToString(byte[] plaintext, byte[] aad) {
        return Bytes.b64url(seal(plaintext, aad));
    }

    public byte[] openString(String box, byte[] aad) {
        byte[] raw;
        try {
            raw = Bytes.b64urlDecode(box);
        } catch (IllegalArgumentException e) {
            throw new SsoException("secretbox.open_failed");
        }
        return open(raw, aad);
    }

    public static String kidOf(byte[] box) {
        if (box == null || box.length < 2) {
            throw new SsoException("secretbox.open_failed");
        }
        int len = box[0] & 0xFF;
        if (len < 1 || len > MAX_KID_LENGTH || box.length < 1 + len) {
            throw new SsoException("secretbox.open_failed");
        }
        String kid = new String(box, 1, len, StandardCharsets.US_ASCII);
        try {
            validateKid(kid);
        } catch (SsoException e) {
            throw new SsoException("secretbox.open_failed");
        }
        return kid;
    }

    public boolean needsReseal(byte[] box) {
        return !kidOf(box).equals(activeKid);
    }

    private static void requireAad(byte[] aad) {
        if (aad == null || aad.length == 0) {
            throw new SsoException("secretbox.aad_required");
        }
    }
}
