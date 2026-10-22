package com.naqqa.observability.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "naqqa.observability")
public class ObservabilityProperties {

    private boolean enabled = true;
    private String path = "/api/observability";
    private String ingestPath = "/api/public/observability";
    private boolean demoData = true;
    private String tokenPepper;
    private String tokenEnv = "live";
    private int maxBodyBytes = 1024 * 1024;
    private int ingestRequestsPerMinute = 1200;
    private int maxRecordsPerBatch = 1000;
    private int maxLogs = 100_000;
    private int maxPoints = 200_000;
    private int maxSpans = 100_000;
    private int maxEventsPerIssue = 200;
    private long retentionIntervalMinutes = 10;
    private String collectionPrefix = "obs_";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public String getIngestPath() {
        return ingestPath;
    }

    public void setIngestPath(String ingestPath) {
        this.ingestPath = ingestPath;
    }

    public boolean isDemoData() {
        return demoData;
    }

    public void setDemoData(boolean demoData) {
        this.demoData = demoData;
    }

    public String getTokenPepper() {
        return tokenPepper;
    }

    public void setTokenPepper(String tokenPepper) {
        this.tokenPepper = tokenPepper;
    }

    public String getTokenEnv() {
        return tokenEnv;
    }

    public void setTokenEnv(String tokenEnv) {
        this.tokenEnv = tokenEnv;
    }

    public int getMaxBodyBytes() {
        return maxBodyBytes;
    }

    public void setMaxBodyBytes(int maxBodyBytes) {
        this.maxBodyBytes = maxBodyBytes;
    }

    public int getIngestRequestsPerMinute() {
        return ingestRequestsPerMinute;
    }

    public void setIngestRequestsPerMinute(int ingestRequestsPerMinute) {
        this.ingestRequestsPerMinute = ingestRequestsPerMinute;
    }

    public int getMaxRecordsPerBatch() {
        return maxRecordsPerBatch;
    }

    public void setMaxRecordsPerBatch(int maxRecordsPerBatch) {
        this.maxRecordsPerBatch = maxRecordsPerBatch;
    }

    public int getMaxLogs() {
        return maxLogs;
    }

    public void setMaxLogs(int maxLogs) {
        this.maxLogs = maxLogs;
    }

    public int getMaxPoints() {
        return maxPoints;
    }

    public void setMaxPoints(int maxPoints) {
        this.maxPoints = maxPoints;
    }

    public int getMaxSpans() {
        return maxSpans;
    }

    public void setMaxSpans(int maxSpans) {
        this.maxSpans = maxSpans;
    }

    public int getMaxEventsPerIssue() {
        return maxEventsPerIssue;
    }

    public void setMaxEventsPerIssue(int maxEventsPerIssue) {
        this.maxEventsPerIssue = maxEventsPerIssue;
    }

    public long getRetentionIntervalMinutes() {
        return retentionIntervalMinutes;
    }

    public void setRetentionIntervalMinutes(long retentionIntervalMinutes) {
        this.retentionIntervalMinutes = retentionIntervalMinutes;
    }

    public String getCollectionPrefix() {
        return collectionPrefix;
    }

    public void setCollectionPrefix(String collectionPrefix) {
        this.collectionPrefix = collectionPrefix;
    }
}
