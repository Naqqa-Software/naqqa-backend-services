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

/**
 * GCS fixes a resumable session's CORS policy when the session is created, not on the uploads that
 * follow. Initiating without an {@code Origin} yields a session whose every later PUT returns 200
 * with no {@code Access-Control-Allow-Origin} — the browser reports a CORS failure on a request that
 * actually succeeded. So getting this header onto the initiate call is the whole fix.
 */
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
        // Read from the request, not config: a session binds to exactly one origin, so localhost
        // and production have to work without separate configuration.
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
        // A scheduled job or a test has no request in scope.
        assertEquals("https://dev.deedakt.com", originOf(service("https://dev.deedakt.com")));
    }

    @Test
    void isNullWhenNothingIsAvailable() {
        // Sending no Origin at all is better than sending a wrong one — a session bound to the
        // wrong origin fails for every browser rather than just being unbound.
        assertNull(originOf(service(null)));
        assertNull(originOf(service("   ")));
    }
}
