package com.naqqa.acs.events;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;

public final class AcsRequestSigner {

    private static final Pattern SECRET_KEY = Pattern.compile("^acs_sk_(live|test)_([a-z2-7]{16})_([a-z2-7]{52})_([0-9a-f]{8})$");
    private static final String B32 = "abcdefghijklmnopqrstuvwxyz234567";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final String keyId;
    private final byte[] key;

    private AcsRequestSigner(String keyId, byte[] key) {
        this.keyId = keyId;
        this.key = key;
    }

    public static AcsRequestSigner fromSecretKey(String secretKey) {
        if (secretKey == null) {
            return null;
        }
        var m = SECRET_KEY.matcher(secretKey.trim());
        if (!m.matches()) {
            return null;
        }
        String body = secretKey.trim().substring(0, secretKey.trim().lastIndexOf('_'));
        if (!crc32Hex(body).equals(m.group(4))) {
            return null;
        }
        byte[] secret = base32Decode(m.group(3));
        if (secret.length != 32) {
            return null;
        }
        String id = m.group(2);
        byte[] derived = hkdfSha256(secret, "acs-req-v1".getBytes(StandardCharsets.UTF_8),
                ("req:" + id).getBytes(StandardCharsets.UTF_8), 32);
        java.util.Arrays.fill(secret, (byte) 0);
        return new AcsRequestSigner(id, derived);
    }

    public String keyId() {
        return keyId;
    }

    public String sign(String method, String path, String query, byte[] body, long timestamp) {
        String nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes(18));
        byte[] canonical = canonical(method, path, query, body, timestamp, nonce, keyId);
        byte[] sig = hmac(key, canonical);
        return "v1;alg=hmac-sha256;ts=" + timestamp + ";nonce=" + nonce + ";sig="
                + Base64.getUrlEncoder().withoutPadding().encodeToString(sig);
    }

    static byte[] canonical(String method, String path, String query, byte[] body, long timestamp, String nonce, String keyId) {
        String bodyHash = HexFormat.of().formatHex(sha256(body == null ? new byte[0] : body));
        return framed("acs-req-v1", method.toUpperCase(java.util.Locale.ROOT), normalizePath(path), normalizeQuery(query),
                bodyHash, Long.toString(timestamp), nonce, keyId);
    }

    public static String normalizePath(String path) {
        List<String> segs = new ArrayList<>();
        for (String seg : path.split("/")) {
            if (seg.isEmpty() || ".".equals(seg)) {
                continue;
            }
            if ("..".equals(seg)) {
                if (!segs.isEmpty()) {
                    segs.remove(segs.size() - 1);
                }
                continue;
            }
            segs.add(seg);
        }
        return "/" + String.join("/", segs);
    }

    static String normalizeQuery(String query) {
        if (query == null || query.isEmpty()) {
            return "";
        }
        String q = query.startsWith("?") ? query.substring(1) : query;
        if (q.isEmpty()) {
            return "";
        }
        List<String[]> pairs = new ArrayList<>();
        for (String part : q.split("&", -1)) {
            int eq = part.indexOf('=');
            String k = eq >= 0 ? part.substring(0, eq) : part;
            String v = eq >= 0 ? part.substring(eq + 1) : "";
            pairs.add(new String[]{decode(k), decode(v)});
        }
        pairs.sort((a, b) -> a[0].equals(b[0]) ? a[1].compareTo(b[1]) : a[0].compareTo(b[0]));
        List<String> out = new ArrayList<>();
        for (String[] p : pairs) {
            out.add(encode(p[0]) + "=" + encode(p[1]));
        }
        return String.join("&", out);
    }

    private static String decode(String s) {
        return URLDecoder.decode(s.replace("+", "%20"), StandardCharsets.UTF_8);
    }

    static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8)
                .replace("+", "%20").replace("%7E", "~")
                .replace("!", "%21").replace("'", "%27").replace("(", "%28").replace(")", "%29").replace("*", "%2A");
    }

    private static byte[] framed(String... fields) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (String f : fields) {
            byte[] b = f.getBytes(StandardCharsets.UTF_8);
            out.writeBytes(ByteBuffer.allocate(4).putInt(b.length).array());
            out.writeBytes(b);
        }
        return out.toByteArray();
    }

    static byte[] hkdfSha256(byte[] ikm, byte[] salt, byte[] info, int length) {
        byte[] prk = hmac(salt, ikm);
        ByteArrayOutputStream okm = new ByteArrayOutputStream();
        byte[] t = new byte[0];
        for (int i = 1; okm.size() < length; i++) {
            ByteArrayOutputStream in = new ByteArrayOutputStream();
            in.writeBytes(t);
            in.writeBytes(info);
            in.write(i);
            t = hmac(prk, in.toByteArray());
            okm.writeBytes(t);
        }
        return java.util.Arrays.copyOf(okm.toByteArray(), length);
    }

    private static byte[] hmac(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (Exception e) {
            throw new IllegalStateException("HMAC unavailable", e);
        }
    }

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static byte[] randomBytes(int n) {
        byte[] b = new byte[n];
        RANDOM.nextBytes(b);
        return b;
    }

    static byte[] base32Decode(String s) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int bits = 0;
        int value = 0;
        for (int i = 0; i < s.length(); i++) {
            int v = B32.indexOf(s.charAt(i));
            if (v < 0) {
                throw new IllegalArgumentException("invalid base32");
            }
            value = (value << 5) | v;
            bits += 5;
            if (bits >= 8) {
                out.write((value >>> (bits - 8)) & 255);
                bits -= 8;
            }
        }
        return out.toByteArray();
    }

    static String crc32Hex(String s) {
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(s.getBytes(StandardCharsets.UTF_8));
        return String.format("%08x", crc.getValue());
    }
}
