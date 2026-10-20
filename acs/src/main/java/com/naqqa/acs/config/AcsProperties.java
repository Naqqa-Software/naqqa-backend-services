package com.naqqa.acs.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.Ordered;

import java.util.ArrayList;
import java.util.List;

@ConfigurationProperties(prefix = "naqqa.acs")
public class AcsProperties {

    public enum Mode {
        MONITOR,
        ENFORCE
    }

    private boolean enabled = false;
    private Mode mode = Mode.MONITOR;
    private String apiUrl = "";
    private String siteId = "";
    private String secretKey = "";
    private String sessionSecret = "";
    private String publicKeys = "";
    private String keysPin = "";
    private long keysRefreshSeconds = 300;
    private boolean devMode = false;
    private long clockToleranceMs = 5000;
    private List<String> trustedProxies = new ArrayList<>(List.of("127.0.0.1/32", "::1/128"));
    private boolean strictIpBinding = false;
    private final Verifier verifier = new Verifier();
    private final Events events = new Events();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Mode getMode() {
        return mode;
    }

    public void setMode(Mode mode) {
        this.mode = mode;
    }

    public String getApiUrl() {
        return apiUrl;
    }

    public void setApiUrl(String apiUrl) {
        this.apiUrl = apiUrl;
    }

    public String getSiteId() {
        return siteId;
    }

    public void setSiteId(String siteId) {
        this.siteId = siteId;
    }

    public String getSecretKey() {
        return secretKey;
    }

    public void setSecretKey(String secretKey) {
        this.secretKey = secretKey;
    }

    public String getSessionSecret() {
        return sessionSecret;
    }

    public void setSessionSecret(String sessionSecret) {
        this.sessionSecret = sessionSecret;
    }

    public String getPublicKeys() {
        return publicKeys;
    }

    public void setPublicKeys(String publicKeys) {
        this.publicKeys = publicKeys;
    }

    public String getKeysPin() {
        return keysPin;
    }

    public void setKeysPin(String keysPin) {
        this.keysPin = keysPin;
    }

    public long getKeysRefreshSeconds() {
        return keysRefreshSeconds;
    }

    public void setKeysRefreshSeconds(long keysRefreshSeconds) {
        this.keysRefreshSeconds = keysRefreshSeconds;
    }

    public boolean isDevMode() {
        return devMode;
    }

    public void setDevMode(boolean devMode) {
        this.devMode = devMode;
    }

    public long getClockToleranceMs() {
        return clockToleranceMs;
    }

    public void setClockToleranceMs(long clockToleranceMs) {
        this.clockToleranceMs = clockToleranceMs;
    }

    public List<String> getTrustedProxies() {
        return trustedProxies;
    }

    public void setTrustedProxies(List<String> trustedProxies) {
        this.trustedProxies = trustedProxies;
    }

    public boolean isStrictIpBinding() {
        return strictIpBinding;
    }

    public void setStrictIpBinding(boolean strictIpBinding) {
        this.strictIpBinding = strictIpBinding;
    }

    public Verifier getVerifier() {
        return verifier;
    }

    public Events getEvents() {
        return events;
    }

    public static class Verifier {

        private boolean enabled = true;
        private List<String> urlPatterns = new ArrayList<>(List.of("/api/*"));
        private int order = Ordered.HIGHEST_PRECEDENCE + 10;
        private List<String> sensitive = new ArrayList<>();
        private List<String> signed = new ArrayList<>();
        private List<String> failOpenPaths = new ArrayList<>(List.of("/api/auth/login", "/api/auth/register", "/api/auth/forgot-password", "/api/auth/reset-password"));

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public List<String> getUrlPatterns() {
            return urlPatterns;
        }

        public void setUrlPatterns(List<String> urlPatterns) {
            this.urlPatterns = urlPatterns;
        }

        public int getOrder() {
            return order;
        }

        public void setOrder(int order) {
            this.order = order;
        }

        public List<String> getSensitive() {
            return sensitive;
        }

        public void setSensitive(List<String> sensitive) {
            this.sensitive = sensitive;
        }

        public List<String> getSigned() {
            return signed;
        }

        public void setSigned(List<String> signed) {
            this.signed = signed;
        }

        public List<String> getFailOpenPaths() {
            return failOpenPaths;
        }

        public void setFailOpenPaths(List<String> failOpenPaths) {
            this.failOpenPaths = failOpenPaths;
        }
    }

    public static class Events {

        private boolean enabled = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }
}
