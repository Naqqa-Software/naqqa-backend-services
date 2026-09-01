package com.naqqa.filestorage.video;

import com.naqqa.filestorage.config.FileStorageProperties;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

/**
 * Mints Cloud CDN signed cookies scoped to a single ladder.
 *
 * <p>The cookie is what replaces per-segment authorisation: the application decides once whether the
 * viewer may watch, and the CDN enforces that decision at the edge for every segment afterwards. The
 * origin bucket stays private throughout — only the CDN's cache-fill service account can read it.
 *
 * <p><b>The prefix is the security boundary.</b> A cookie is signed for
 * {@code <baseUrl>/<vodPrefix>/<groupId>/} and is rejected for anything outside it, so a viewer
 * entitled to one lesson cannot use their cookie to reach another. That is why the ladder layout
 * puts each group in its own directory.
 *
 * <p>Cloud CDN validates HMAC-SHA1 over the policy string. SHA-1 is not our choice — it is the
 * algorithm the service defines — and it is used here as a keyed MAC, where the collision weaknesses
 * that rule SHA-1 out for signatures do not apply.
 */
public class CdnSignedCookieService {

    /** The cookie name Cloud CDN looks for. Not configurable. */
    public static final String COOKIE_NAME = "Cloud-CDN-Cookie";

    private final FileStorageProperties props;

    public CdnSignedCookieService(FileStorageProperties props) {
        this.props = props;
    }

    public boolean isEnabled() {
        FileStorageProperties.Hls.Cdn cdn = props.getHls().getCdn();
        return cdn.isEnabled()
                && notBlank(cdn.getBaseUrl())
                && notBlank(cdn.getKeyName())
                && notBlank(cdn.getKeyValue());
    }

    /** The path a cookie for {@code groupId} is scoped to, and the path the cookie must be set on. */
    public String cookiePath(String groupId) {
        return "/" + props.getTranscode().getVodPrefix() + "/" + groupId + "/";
    }

    /** Absolute master playlist URL served by the CDN. */
    public String masterPlaylistUrl(String groupId) {
        return trimmedBaseUrl() + cookiePath(groupId) + "master.m3u8";
    }

    /** Seconds the issued cookie remains valid — also the revocation window. */
    public int ttlSeconds() {
        return props.getHls().getCdn().getCookieTtlSeconds();
    }

    /**
     * Builds the signed cookie value for one ladder.
     *
     * @param groupId the ladder the viewer has been authorised for
     */
    public String sign(String groupId) {
        if (!isEnabled()) {
            throw new IllegalStateException("CDN signed cookies are not configured");
        }
        FileStorageProperties.Hls.Cdn cdn = props.getHls().getCdn();

        String urlPrefix = trimmedBaseUrl() + cookiePath(groupId);
        String encodedPrefix = base64Url(urlPrefix.getBytes(StandardCharsets.UTF_8));
        long expires = Instant.now().getEpochSecond() + cdn.getCookieTtlSeconds();

        String policy = "URLPrefix=" + encodedPrefix
                + ":Expires=" + expires
                + ":KeyName=" + cdn.getKeyName();

        return policy + ":Signature=" + base64Url(hmacSha1(decodeKey(cdn.getKeyValue()), policy));
    }

    private byte[] hmacSha1(byte[] key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to sign CDN cookie", e);
        }
    }

    /** The key is stored base64url without padding, as {@code add-signed-url-key} expects it. */
    private static byte[] decodeKey(String keyValue) {
        String padded = keyValue.trim();
        int remainder = padded.length() % 4;
        if (remainder != 0) {
            padded += "=".repeat(4 - remainder);
        }
        return Base64.getUrlDecoder().decode(padded);
    }

    private static String base64Url(byte[] raw) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    private String trimmedBaseUrl() {
        return props.getHls().getCdn().getBaseUrl().replaceAll("/+$", "");
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
