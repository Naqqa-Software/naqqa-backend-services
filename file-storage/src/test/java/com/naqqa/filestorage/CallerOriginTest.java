package com.naqqa.filestorage;

import com.naqqa.filestorage.config.FileStorageProperties;
import com.naqqa.filestorage.service.FileStorageService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.junit.jupiter.api.Assertions.*;

class CallerOriginTest {

    private FileStorageService service(String configuredFallback) {
        FileStorageProperties props = new FileStorageProperties();
        props.getUpload().setBrowserOrigin(configuredFallback);
        return new FileStorageService(null, null, props);
    }

    private String originOf(FileStorageService s) {
        return (String) ReflectionTestUtils.invokeMethod(s, "callerOrigin");
    }

    @AfterEach
    void clearRequest() {
        RequestContextHolder.resetRequestAttributes();
    }

    private void inboundRequestWithOrigin(String origin) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (origin != null) {
            request.addHeader("Origin", origin);
        }
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @Test
    void takesTheOriginFromTheBrowsersOwnRequest() {
        inboundRequestWithOrigin("https://dev.deedakt.com");
        assertEquals("https://dev.deedakt.com", originOf(service("https://fallback.example")));
    }

    @Test
    void localhostDevelopmentBindsToLocalhost() {
        inboundRequestWithOrigin("http://localhost:4200");
        assertEquals("http://localhost:4200", originOf(service("https://dev.deedakt.com")));
    }

    @Test
    void fallsBackToConfigWhenTheRequestCarriesNoOrigin() {
        inboundRequestWithOrigin(null);
        assertEquals("https://dev.deedakt.com", originOf(service("https://dev.deedakt.com")));
    }

    @Test
    void fallsBackToConfigOutsideAnyWebRequest() {
        assertEquals("https://dev.deedakt.com", originOf(service("https://dev.deedakt.com")));
    }

    @Test
    void isNullWhenNothingIsAvailable() {
        assertNull(originOf(service(null)));
        assertNull(originOf(service("   ")));
    }
}
