package com.naqqa.filestorage.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@Getter
@Setter
@ConfigurationProperties(prefix = "gcs.lib")
public class FileStorageProperties {

    private String configFile;

    private String projectId;

    private String bucketName;

    private String publicBucketName;

    private String publicPrefix = "public/";

    private String publicBaseUrl;

    private boolean uniqueObjectNames = true;

    private final Upload upload = new Upload();
    private final Transcode transcode = new Transcode();
    private final Hls hls = new Hls();

    @Getter
    @Setter
    public static class Upload {

        public enum Mode {
            RESUMABLE,
            SIGNED_PUT
        }

        private Mode mode = Mode.RESUMABLE;

        private int signedUrlExpiryMinutes = 60;

        private String browserOrigin;
    }

    @Getter
    @Setter
    public static class Transcode {

        private boolean enabled = false;

        private String jobName = "transcoder";

        private String region = "europe-west1";

        private String projectId;

        private String vodPrefix = "vod";

        private List<String> rungs = List.of("1080:4500k:128k", "720:2500k:128k", "360:800k:64k");

        private String callbackUrl;

        private String callbackToken;

        private boolean exposeCallbackEndpoint = true;

        private String callbackPath = "/api/internal/transcode/callback";
    }

    @Getter
    @Setter
    public static class Hls {

        private boolean enabled = false;

        private String basePath = "/api/media";

        private long accessCacheTtlSeconds = 120;

        private int accessCacheMaxEntries = 50_000;

        private final Cdn cdn = new Cdn();

        @Getter
        @Setter
        public static class Cdn {

            private boolean enabled = false;

            private String baseUrl;

            private String keyName;

            private String keyValue;

            private int cookieTtlSeconds = 300;
        }
    }
}
