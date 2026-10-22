package com.naqqa.filestorage.video;

import com.naqqa.filestorage.config.FileStorageProperties;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

public class CdnSignedCookieService {

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

    public String cookiePath(String groupId) {
        return "/" + props.getTranscode().getVodPrefix() + "/" + groupId + "/";
    }

    public String masterPlaylistUrl(String groupId) {
        return trimmedBaseUrl() + cookiePath(groupId) + "master.m3u8";
    }

    public int ttlSeconds() {
        return props.getHls().getCdn().getCookieTtlSeconds();
    }

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
