package com.naqqa.tts.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@ConfigurationProperties(prefix = "naqqa.tts")
public class TtsProperties {

    private boolean enabled = false;
    private String path = "/api/public/tts";
    private String dataDir = System.getProperty("java.io.tmpdir") + "/naqqa-tts";
    private Piper piper = new Piper();
    private Map<String, Voice> voices = new LinkedHashMap<>();
    private Map<String, Map<String, String>> lexicon = new LinkedHashMap<>();
    private int maxChars = 400;
    private long acquireTimeoutMs = 300;
    private long synthTimeoutMs = 15000;
    private long cacheMaxMb = 500;
    private int rateLimitPerMinute = 40;
    private long idleUnloadMinutes = 20;
    private int breakerFailures = 3;
    private long breakerOpenMs = 60000;
    private long cacheMaxAgeSeconds = 2592000;

    public static class Piper {
        private String binary;
        private boolean autoInstall = true;
        private String release = "2023.11.14-2";
        private String downloadUrl;
        private String voicesBaseUrl = "https://huggingface.co/rhasspy/piper-voices/resolve/v1.0.0";
        private List<String> commandPrefix = new ArrayList<>();
        private double sentenceSilence = 0.15;

        public String getBinary() { return binary; }
        public void setBinary(String binary) { this.binary = binary; }
        public boolean isAutoInstall() { return autoInstall; }
        public void setAutoInstall(boolean autoInstall) { this.autoInstall = autoInstall; }
        public String getRelease() { return release; }
        public void setRelease(String release) { this.release = release; }
        public String getDownloadUrl() { return downloadUrl; }
        public void setDownloadUrl(String downloadUrl) { this.downloadUrl = downloadUrl; }
        public String getVoicesBaseUrl() { return voicesBaseUrl; }
        public void setVoicesBaseUrl(String voicesBaseUrl) { this.voicesBaseUrl = voicesBaseUrl; }
        public List<String> getCommandPrefix() { return commandPrefix; }
        public void setCommandPrefix(List<String> commandPrefix) { this.commandPrefix = commandPrefix; }
        public double getSentenceSilence() { return sentenceSilence; }
        public void setSentenceSilence(double sentenceSilence) { this.sentenceSilence = sentenceSilence; }
    }

    public static class Voice {
        private String name;
        private String model;
        private String url;
        private Double lengthScale;
        private Integer speaker;

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }
        public Double getLengthScale() { return lengthScale; }
        public void setLengthScale(Double lengthScale) { this.lengthScale = lengthScale; }
        public Integer getSpeaker() { return speaker; }
        public void setSpeaker(Integer speaker) { this.speaker = speaker; }

        public String id() {
            return (name == null ? model : name) + "|" + (lengthScale == null ? "" : lengthScale) + "|" + (speaker == null ? "" : speaker);
        }
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }
    public String getDataDir() { return dataDir; }
    public void setDataDir(String dataDir) { this.dataDir = dataDir; }
    public Piper getPiper() { return piper; }
    public void setPiper(Piper piper) { this.piper = piper; }
    public Map<String, Voice> getVoices() { return voices; }
    public void setVoices(Map<String, Voice> voices) { this.voices = voices; }
    public Map<String, Map<String, String>> getLexicon() { return lexicon; }
    public void setLexicon(Map<String, Map<String, String>> lexicon) { this.lexicon = lexicon; }
    public int getMaxChars() { return maxChars; }
    public void setMaxChars(int maxChars) { this.maxChars = maxChars; }
    public long getAcquireTimeoutMs() { return acquireTimeoutMs; }
    public void setAcquireTimeoutMs(long acquireTimeoutMs) { this.acquireTimeoutMs = acquireTimeoutMs; }
    public long getSynthTimeoutMs() { return synthTimeoutMs; }
    public void setSynthTimeoutMs(long synthTimeoutMs) { this.synthTimeoutMs = synthTimeoutMs; }
    public long getCacheMaxMb() { return cacheMaxMb; }
    public void setCacheMaxMb(long cacheMaxMb) { this.cacheMaxMb = cacheMaxMb; }
    public int getRateLimitPerMinute() { return rateLimitPerMinute; }
    public void setRateLimitPerMinute(int rateLimitPerMinute) { this.rateLimitPerMinute = rateLimitPerMinute; }
    public long getIdleUnloadMinutes() { return idleUnloadMinutes; }
    public void setIdleUnloadMinutes(long idleUnloadMinutes) { this.idleUnloadMinutes = idleUnloadMinutes; }
    public int getBreakerFailures() { return breakerFailures; }
    public void setBreakerFailures(int breakerFailures) { this.breakerFailures = breakerFailures; }
    public long getBreakerOpenMs() { return breakerOpenMs; }
    public void setBreakerOpenMs(long breakerOpenMs) { this.breakerOpenMs = breakerOpenMs; }
    public long getCacheMaxAgeSeconds() { return cacheMaxAgeSeconds; }
    public void setCacheMaxAgeSeconds(long cacheMaxAgeSeconds) { this.cacheMaxAgeSeconds = cacheMaxAgeSeconds; }
}
