package com.naqqa.auth.config.authorities;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.TreeSet;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Compact, lossless form of a large authority set inside a signed access token (PERF-FE-1).
 *
 * <p>An administrator holds about 150 authorities; listed as a JSON array they made the access token
 * 4.3 KB, over the 4096 byte cookie limit of browsers, so the {@code access_token} cookie was dropped.
 * Above {@link #INLINE_LIMIT} characters the token carries the claim {@code authz} =
 * {@code "d1." + base64url(raw deflate(names sorted, joined by '\n'))} instead of {@code authorities}.
 * The claim is covered by the token signature like every other claim, decoding is verified before it
 * is read, the inflated size and entry count are capped, and any malformed value is refused (the
 * token is treated as invalid, never as a token without authorities).</p>
 *
 * <p>AU-BE-66 hardening: the encoded length is capped before anything is decoded or inflated, the inflated
 * text must be the canonical form this class writes (names strictly ascending, so no duplicates, no empty
 * or control-character names, each at most {@link #MAX_NAME} characters), only the version prefix
 * {@code d1.} is known, and a token carrying both {@code authz} and {@code authorities} is refused by
 * {@link JwtToAuthorities#fromClaims}. The claim is a snapshot of the server-side set at minting time,
 * exactly like the plain list: refresh, role switch and step-up re-read it from the database, and per
 * request {@code AuthoritiesCustomizer}s (naqqa-os per-user revocations) apply to it unchanged.</p>
 */
public final class CompactAuthorities {

    public static final String CLAIM = "authz";
    public static final String PREFIX = "d1.";
    /** A plain list longer than this (sum of name lengths plus separators) is sent compact. */
    public static final int INLINE_LIMIT = 1024;
    public static final int MAX_INFLATED = 64 * 1024;
    public static final int MAX_ENTRIES = 4096;
    /** Longest accepted claim value (prefix plus base64url), checked before decoding or inflating. */
    public static final int MAX_ENCODED = 12 * 1024;
    public static final int MAX_NAME = 256;

    private CompactAuthorities() {
    }

    /** True when {@code names} must travel compact: too long inline and every name is encodable. */
    public static boolean shouldCompact(Collection<String> names) {
        int length = 0;
        for (String n : names) {
            if (!validName(n)) {
                return false;
            }
            length += n.length() + 3;
        }
        return length > INLINE_LIMIT && names.size() <= MAX_ENTRIES;
    }

    private static boolean validName(String n) {
        if (n == null || n.isEmpty() || n.length() > MAX_NAME) {
            return false;
        }
        for (int i = 0; i < n.length(); i++) {
            char c = n.charAt(i);
            if (c < 0x20 || c == 0x7f) {
                return false;
            }
        }
        return true;
    }

    public static String encode(Collection<String> names) {
        byte[] raw = String.join("\n", new TreeSet<>(names)).getBytes(StandardCharsets.UTF_8);
        if (raw.length > MAX_INFLATED) {
            throw new IllegalArgumentException("authority set too large");
        }
        Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION, true);
        try {
            deflater.setInput(raw);
            deflater.finish();
            ByteArrayOutputStream out = new ByteArrayOutputStream(raw.length / 2 + 16);
            byte[] buf = new byte[1024];
            while (!deflater.finished()) {
                out.write(buf, 0, deflater.deflate(buf));
            }
            return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(out.toByteArray());
        } finally {
            deflater.end();
        }
    }

    /** @throws IllegalArgumentException on any malformed, oversized or non canonical value */
    public static List<String> decode(Object claim) {
        if (!(claim instanceof String s) || !s.startsWith(PREFIX)) {
            throw new IllegalArgumentException("malformed authz claim");
        }
        if (s.length() > MAX_ENCODED) {
            throw new IllegalArgumentException("authz claim too large");
        }
        byte[] compressed;
        try {
            compressed = Base64.getUrlDecoder().decode(s.substring(PREFIX.length()));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("malformed authz claim");
        }
        Inflater inflater = new Inflater(true);
        ByteArrayOutputStream out = new ByteArrayOutputStream(compressed.length * 4);
        try {
            inflater.setInput(compressed);
            byte[] buf = new byte[1024];
            while (!inflater.finished()) {
                int n = inflater.inflate(buf);
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
                    throw new IllegalArgumentException("truncated authz claim");
                }
                out.write(buf, 0, n);
                if (out.size() > MAX_INFLATED) {
                    throw new IllegalArgumentException("authz claim too large");
                }
            }
            if (inflater.getRemaining() != 0) {
                throw new IllegalArgumentException("trailing data in authz claim");
            }
        } catch (DataFormatException e) {
            throw new IllegalArgumentException("malformed authz claim");
        } finally {
            inflater.end();
        }
        String text = out.toString(StandardCharsets.UTF_8);
        if (text.isEmpty()) {
            return List.of();
        }
        String[] parts = text.split("\n", -1);
        if (parts.length > MAX_ENTRIES) {
            throw new IllegalArgumentException("authz claim too large");
        }
        List<String> names = new ArrayList<>(parts.length);
        String previous = null;
        for (String p : parts) {
            if (!validName(p)) {
                throw new IllegalArgumentException("malformed authz claim");
            }
            if (previous != null && previous.compareTo(p) >= 0) {
                // not the canonical TreeSet order this class writes: duplicates or a foreign encoder
                throw new IllegalArgumentException("non canonical authz claim");
            }
            names.add(p);
            previous = p;
        }
        return names;
    }
}
