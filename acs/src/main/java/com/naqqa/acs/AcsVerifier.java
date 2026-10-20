package com.naqqa.acs;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class AcsVerifier {
    public static final String ATTEST_PREFIX = "acs1";
    public static final String ACTION_PREFIX = "acsact1";
    public static final int MAX_TOKEN_BYTES = 4096;
    public static final long SIG_SKEW_MS = 30_000;
    private static final byte[] ED25519_SPKI_PREFIX = hex("302a300506032b6570032100");
    private static final Pattern NONCE = Pattern.compile("^[A-Za-z0-9_-]{8,64}$");
    private static final Pattern B64URL = Pattern.compile("^[A-Za-z0-9_-]*$");

    public static final class Result {
        public final boolean ok;
        public final String reason;
        public final Map<String, Object> payload;

        Result(boolean ok, String reason, Map<String, Object> payload) {
            this.ok = ok;
            this.reason = reason;
            this.payload = payload;
        }

        static Result fail(String reason) {
            return new Result(false, reason, Collections.emptyMap());
        }
    }

    private final Map<String, PublicKey> keys = new HashMap<>();
    private final String siteId;
    private final byte[] sessionSecret;
    private final byte[] hashKey;
    private final long toleranceMs;
    private final NonceStore actionJti = new NonceStore(240_000, 500_000);
    private final NonceStore sigNonces = new NonceStore(2 * SIG_SKEW_MS, 500_000);

    public AcsVerifier(Map<String, String> publicKeys, String siteId, byte[] sessionSecret, long toleranceMs) {
        this(publicKeys, siteId, sessionSecret, toleranceMs, null);
    }

    public AcsVerifier(Map<String, String> publicKeys, String siteId, byte[] sessionSecret, long toleranceMs, byte[] hashKey) {
        if (siteId == null || siteId.isEmpty()) throw new IllegalArgumentException("siteId required");
        this.siteId = siteId;
        this.sessionSecret = sessionSecret == null ? null : sessionSecret.clone();
        this.hashKey = hashKey == null ? null : hashKey.clone();
        this.toleranceMs = toleranceMs;
        try {
            KeyFactory kf = KeyFactory.getInstance("Ed25519");
            for (Map.Entry<String, String> e : publicKeys.entrySet()) {
                byte[] raw = b64(e.getValue());
                if (raw == null || raw.length != 32) throw new IllegalArgumentException("bad public key for kid " + e.getKey());
                byte[] spki = new byte[ED25519_SPKI_PREFIX.length + 32];
                System.arraycopy(ED25519_SPKI_PREFIX, 0, spki, 0, ED25519_SPKI_PREFIX.length);
                System.arraycopy(raw, 0, spki, ED25519_SPKI_PREFIX.length, 32);
                keys.put(e.getKey(), kf.generatePublic(new X509EncodedKeySpec(spki)));
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Ed25519 unavailable (JDK 15+ required)", e);
        }
    }

    public Result verifyAttestation(String token, long nowMs) {
        return verify(token, nowMs, ATTEST_PREFIX);
    }

    public String keyedHash(String s) {
        if (hashKey == null) throw new IllegalStateException("hashKey not configured");
        byte[] mac = hmac(hashKey, s.getBytes(StandardCharsets.UTF_8));
        byte[] cut = new byte[12];
        System.arraycopy(mac, 0, cut, 0, 12);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(cut);
    }

    public Result verifyAttestationBound(String token, long nowMs, String userAgent, String clientIp, boolean strictIp) {
        Result r = verifyAttestation(token, nowMs);
        if (!r.ok || hashKey == null) return r;
        Object uah = r.payload.get("uah");
        if (uah instanceof String && !MessageDigest.isEqual(((String) uah).getBytes(StandardCharsets.UTF_8), keyedHash(userAgent == null ? "" : userAgent).getBytes(StandardCharsets.UTF_8))) {
            return Result.fail("ua-mismatch");
        }
        Object ipc = r.payload.get("ipc");
        if (ipc instanceof String) {
            String cls = IpUtil.ipClass(clientIp == null ? "" : clientIp);
            String expected = keyedHash(cls == null ? "unknown" : cls);
            if (!MessageDigest.isEqual(((String) ipc).getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8))) {
                return strictIp ? Result.fail("ip-class") : new Result(true, "ip-class-changed", r.payload);
            }
        }
        return r;
    }

    public Result verifyAction(String token, long nowMs, String expectedAction, String expectedSub) {
        Result r = verify(token, nowMs, ACTION_PREFIX);
        if (!r.ok) return r;
        if (!expectedAction.equals(r.payload.get("act"))) return Result.fail("action");
        if (!expectedSub.equals(r.payload.get("sub"))) return Result.fail("subject");
        Object jti = r.payload.get("jti");
        if (!(jti instanceof String) || !actionJti.checkAndStore("act:" + jti, nowMs)) return Result.fail("replay");
        return r;
    }

    private Result verify(String token, long nowMs, String expectedPrefix) {
        if (token == null || token.isEmpty()) return Result.fail("empty");
        if (token.getBytes(StandardCharsets.UTF_8).length > MAX_TOKEN_BYTES) return Result.fail("oversized");
        String[] parts = token.split("\\.", -1);
        if (parts.length != 4) return Result.fail("segments");
        String prefix = parts[0], kid = parts[1], payloadB64 = parts[2], sigB64 = parts[3];
        if (!prefix.equals(expectedPrefix)) return Result.fail("prefix");
        byte[] sig = b64(sigB64);
        byte[] payloadBytes = b64(payloadB64);
        if (sig == null || payloadBytes == null) return Result.fail("encoding");
        PublicKey pk = keys.get(kid);
        if (pk == null || sig.length != 64) return Result.fail("signature");
        try {
            Signature v = Signature.getInstance("Ed25519");
            v.initVerify(pk);
            v.update((prefix + "." + kid + "." + payloadB64).getBytes(StandardCharsets.UTF_8));
            if (!v.verify(sig)) return Result.fail("signature");
        } catch (Exception e) {
            return Result.fail("signature");
        }
        Object parsed;
        try {
            parsed = Json.parse(new String(payloadBytes, StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            return Result.fail("payload-json");
        }
        if (!(parsed instanceof Map)) return Result.fail("payload-type");
        @SuppressWarnings("unchecked")
        Map<String, Object> p = (Map<String, Object>) parsed;
        Double iat = num(p.get("iat")), nbf = num(p.get("nbf")), exp = num(p.get("exp"));
        if (iat == null || nbf == null || exp == null) return Result.fail("claims");
        if (nowMs + toleranceMs < nbf) return Result.fail("nbf");
        if (nowMs - toleranceMs >= exp) return Result.fail("expired");
        if (!siteId.equals(p.get("aud"))) return Result.fail("audience");
        Object sub = p.get("sub");
        if (!(sub instanceof String) || ((String) sub).isEmpty()) return Result.fail("claims");
        return new Result(true, null, Collections.unmodifiableMap(p));
    }

    public byte[] sessionKey(String sub) {
        if (sessionSecret == null) throw new IllegalStateException("sessionSecret not configured");
        return hkdf(sessionSecret, "acs-sess".getBytes(StandardCharsets.UTF_8), ("sub:" + sub).getBytes(StandardCharsets.UTF_8), 32);
    }

    public Result verifySessionSig(String sub, String header, String method, String path, String query, long nowMs) {
        if (header == null) return Result.fail("missing");
        String[] parts = header.split("\\.", -1);
        if (parts.length != 4 || !"v1".equals(parts[0])) return Result.fail("bad");
        long ts;
        try {
            ts = Long.parseLong(parts[1]);
        } catch (NumberFormatException e) {
            return Result.fail("bad");
        }
        String nonce = parts[2];
        if (!NONCE.matcher(nonce).matches()) return Result.fail("bad");
        if (Math.abs(nowMs - ts) > SIG_SKEW_MS) return Result.fail("stale");
        byte[] mac = b64(parts[3]);
        if (mac == null) return Result.fail("bad");
        String q = query == null ? "" : (query.startsWith("?") ? query.substring(1) : query);
        byte[] expected = hmac(sessionKey(sub), framed("acs-sess-v1", method.toUpperCase(), path, q, Long.toString(ts), nonce));
        if (!MessageDigest.isEqual(mac, expected)) return Result.fail("bad");
        if (!sigNonces.checkAndStore(sub + ":" + nonce, nowMs)) return Result.fail("replay");
        return new Result(true, null, Collections.emptyMap());
    }

    public byte[] originKey() {
        if (sessionSecret == null) throw new IllegalStateException("sessionSecret not configured");
        return hkdf(sessionSecret, "acs-origin".getBytes(StandardCharsets.UTF_8), "v1".getBytes(StandardCharsets.UTF_8), 32);
    }

    public Result verifyGuardAssertion(String header, String method, String path, String query, long nowMs) {
        if (header == null || sessionSecret == null) return Result.fail("missing");
        String[] parts = header.split("\\.", -1);
        if (parts.length != 4 || !"v1".equals(parts[0])) return Result.fail("bad");
        long ts;
        try {
            ts = Long.parseLong(parts[1]);
        } catch (NumberFormatException e) {
            return Result.fail("bad");
        }
        String tier = parts[2];
        if (!tier.matches("^[a-z]{1,16}$")) return Result.fail("bad");
        if (Math.abs(nowMs - ts) > SIG_SKEW_MS) return Result.fail("stale");
        byte[] mac = b64(parts[3]);
        if (mac == null) return Result.fail("bad");
        String q = query == null ? "" : (query.startsWith("?") ? query.substring(1) : query);
        byte[] expected = hmac(originKey(), framed("acs-guard-v1", method.toUpperCase(), path, q, Long.toString(ts), tier));
        if (!MessageDigest.isEqual(mac, expected)) return Result.fail("bad");
        if (!sigNonces.checkAndStore("guard:" + parts[3], nowMs)) return Result.fail("replay");
        Map<String, Object> p = new HashMap<>();
        p.put("tier", tier);
        return new Result(true, null, Collections.unmodifiableMap(p));
    }

    public static byte[] framed(String... fields) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (String f : fields) {
            byte[] b = f.getBytes(StandardCharsets.UTF_8);
            out.write((b.length >>> 24) & 0xff);
            out.write((b.length >>> 16) & 0xff);
            out.write((b.length >>> 8) & 0xff);
            out.write(b.length & 0xff);
            out.write(b, 0, b.length);
        }
        return out.toByteArray();
    }

    public static byte[] hmac(byte[] key, byte[] data) {
        try {
            Mac m = Mac.getInstance("HmacSHA256");
            m.init(new SecretKeySpec(key, "HmacSHA256"));
            return m.doFinal(data);
        } catch (Exception e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }

    public static byte[] hkdf(byte[] ikm, byte[] salt, byte[] info, int length) {
        byte[] prk = hmac(salt, ikm);
        ByteArrayOutputStream okm = new ByteArrayOutputStream();
        byte[] t = new byte[0];
        for (int i = 1; okm.size() < length; i++) {
            byte[] in = new byte[t.length + info.length + 1];
            System.arraycopy(t, 0, in, 0, t.length);
            System.arraycopy(info, 0, in, t.length, info.length);
            in[in.length - 1] = (byte) i;
            t = hmac(prk, in);
            okm.write(t, 0, t.length);
        }
        byte[] r = okm.toByteArray();
        byte[] out = new byte[length];
        System.arraycopy(r, 0, out, 0, length);
        return out;
    }

    public static byte[] b64(String s) {
        if (s == null || !B64URL.matcher(s).matches() || s.length() % 4 == 1) return null;
        try {
            return Base64.getUrlDecoder().decode(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public static byte[] hex(String h) {
        byte[] out = new byte[h.length() / 2];
        for (int i = 0; i < out.length; i++) out[i] = (byte) Integer.parseInt(h.substring(i * 2, i * 2 + 2), 16);
        return out;
    }

    public static String toHex(byte[] b) {
        StringBuilder s = new StringBuilder();
        for (byte x : b) s.append(String.format("%02x", x & 0xff));
        return s.toString();
    }

    private static Double num(Object o) {
        if (o instanceof Long) return ((Long) o).doubleValue();
        if (o instanceof Double && Double.isFinite((Double) o)) return (Double) o;
        return null;
    }
}
