package com.naqqa.filestorage;

import com.naqqa.filestorage.config.FileStorageProperties;
import com.naqqa.filestorage.video.CdnSignedCookieService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class CdnSignedCookieServiceTest {

    private FileStorageProperties props;
    private CdnSignedCookieService service;

    @BeforeEach
    void setUp() {
        props = new FileStorageProperties();
        props.getTranscode().setVodPrefix("vod");
        var cdn = props.getHls().getCdn();
        cdn.setEnabled(true);
        cdn.setBaseUrl("https://dev.deedakt.com");
        cdn.setKeyName("vod-key-1");
        cdn.setKeyValue(System.getProperty("cdn.key", "dGVzdC1rZXktbm90LXJlYWw"));
        cdn.setCookieTtlSeconds(300);
        service = new CdnSignedCookieService(props);
    }

    @Test
    void scopesTheCookieToOneLadder() {
        assertEquals("/vod/42/", service.cookiePath("42"));
        assertEquals("https://dev.deedakt.com/vod/42/master.m3u8", service.masterPlaylistUrl("42"));
    }

    @Test
    void producesAWellFormedPolicy() {
        String cookie = service.sign("42");
        assertTrue(cookie.startsWith("URLPrefix="), cookie);
        assertTrue(cookie.contains(":Expires="), cookie);
        assertTrue(cookie.contains(":KeyName=vod-key-1"), cookie);
        assertTrue(cookie.contains(":Signature="), cookie);
        String sig = cookie.substring(cookie.indexOf(":Signature=") + 11);
        assertFalse(sig.contains("+") || sig.contains("/") || sig.contains("="), sig);
    }

    @Test
    void differentLaddersProduceDifferentSignatures() {
        assertNotEquals(
                service.sign("42").split(":Signature=")[1],
                service.sign("43").split(":Signature=")[1]);
    }

    @Test
    void refusesToSignWhenNotConfigured() {
        props.getHls().getCdn().setKeyValue("  ");
        assertFalse(service.isEnabled());
        assertThrows(IllegalStateException.class, () -> service.sign("42"));
    }

    @Test
    void emitCookieForLiveCheck() throws Exception {
        String out = System.getProperty("cdn.out");
        if (out == null) return;
        Files.writeString(Path.of(out), service.sign("_probe"));
    }
}
