package com.naqqa.storage.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "naqqa.storage")
public class StorageProperties {

    private final Gcs gcs = new Gcs();
    private final Image image = new Image();

    public Gcs getGcs() {
        return gcs;
    }

    public Image getImage() {
        return image;
    }

    public static class Gcs {
        private String projectId;
        private String credentialsLocation;
        private String publicBucket;
        private String privateBucket;
        private String publicBaseUrl = "https://storage.googleapis.com";
        private int signedUrlTtlSeconds = 300;

        public String getProjectId() { return projectId; }
        public void setProjectId(String projectId) { this.projectId = projectId; }
        public String getCredentialsLocation() { return credentialsLocation; }
        public void setCredentialsLocation(String credentialsLocation) { this.credentialsLocation = credentialsLocation; }
        public String getPublicBucket() { return publicBucket; }
        public void setPublicBucket(String publicBucket) { this.publicBucket = publicBucket; }
        public String getPrivateBucket() { return privateBucket; }
        public void setPrivateBucket(String privateBucket) { this.privateBucket = privateBucket; }
        public String getPublicBaseUrl() { return publicBaseUrl; }
        public void setPublicBaseUrl(String publicBaseUrl) { this.publicBaseUrl = publicBaseUrl; }
        public int getSignedUrlTtlSeconds() { return signedUrlTtlSeconds; }
        public void setSignedUrlTtlSeconds(int signedUrlTtlSeconds) { this.signedUrlTtlSeconds = signedUrlTtlSeconds; }
    }

    public static class Image {
        private boolean enabled = true;
        private int quality = 82;
        private List<Integer> widths = List.of(480, 960, 1600);
        private int fullSizeMaxWidth = 2400;
        private long maxPixels = 100_000_000L;
        private long maxDecodePixels = 40_000_000L;
        private int maxConcurrent = 2;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getQuality() { return quality; }
        public void setQuality(int quality) { this.quality = quality; }
        public List<Integer> getWidths() { return widths; }
        public void setWidths(List<Integer> widths) { this.widths = widths; }
        public int getFullSizeMaxWidth() { return fullSizeMaxWidth; }
        public void setFullSizeMaxWidth(int fullSizeMaxWidth) { this.fullSizeMaxWidth = fullSizeMaxWidth; }
        public long getMaxPixels() { return maxPixels; }
        public void setMaxPixels(long maxPixels) { this.maxPixels = maxPixels; }
        public long getMaxDecodePixels() { return maxDecodePixels; }
        public void setMaxDecodePixels(long maxDecodePixels) { this.maxDecodePixels = maxDecodePixels; }
        public int getMaxConcurrent() { return maxConcurrent; }
        public void setMaxConcurrent(int maxConcurrent) { this.maxConcurrent = maxConcurrent; }
    }
}
