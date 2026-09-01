package com.naqqa.filestorage.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Configuration for the file-storage library, under the {@code gcs.lib} prefix.
 *
 * <p>Only {@link #configFile}, {@link #projectId} and {@link #bucketName} are required. Every other
 * section is opt-in and inert until switched on, so an application that just wants "upload a file to
 * a bucket and hand back a signed URL" configures three properties and gets exactly the behaviour it
 * had before any of this existed.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "gcs.lib")
public class FileStorageProperties {

    /** Classpath service-account JSON. Leave blank to use Application Default Credentials. */
    private String configFile;

    private String projectId;

    /** Private bucket. Objects here are served signed, or proxied by the application. */
    private String bucketName;

    /**
     * Optional world-readable bucket for objects whose key starts with {@link #publicPrefix}.
     *
     * <p>When set, those keys are stored here and served as plain unsigned URLs — cheap and
     * CDN-friendly, with no per-request signBlob round-trip. Blank keeps every object in
     * {@link #bucketName}, signed, which is the stock single-bucket behaviour.
     */
    private String publicBucketName;

    /** Key prefix that routes an object to {@link #publicBucketName}. */
    private String publicPrefix = "public/";

    /**
     * Origin to serve public objects from, e.g. {@code https://cdn.example.com}.
     *
     * <p>Blank serves them straight from {@code storage.googleapis.com}. Set this to put a CDN or
     * load balancer in front; the object key is appended unchanged, so a path rule matching
     * {@link #publicPrefix} maps through with no rewriting.
     */
    private String publicBaseUrl;

    /**
     * Append a random token to every stored object name.
     *
     * <p>The object key is {@code directory/<millis>_<fileName>}, and a millisecond is not unique:
     * two uploads of the same filename into the same directory in the same millisecond collide, the
     * second overwriting the first. Parallel upload queues make that reachable, not theoretical.
     * The user-facing {@code originalFileName} is unaffected.
     */
    private boolean uniqueObjectNames = true;

    private final Upload upload = new Upload();
    private final Transcode transcode = new Transcode();
    private final Hls hls = new Hls();

    /** How a large upload is handed to the client. */
    @Getter
    @Setter
    public static class Upload {

        public enum Mode {
            /**
             * A genuine GCS resumable session. The client may upload in chunks, and can resume
             * after an interruption instead of restarting. Required for large files: the session
             * stays valid for a week, and no single request has to carry the whole object.
             */
            RESUMABLE,
            /**
             * One signed PUT of the entire object, valid for {@link #signedUrlExpiryMinutes}.
             * Simple, but the whole transfer must finish inside that window and a failure at 95%
             * means starting again. Fine for small assets.
             */
            SIGNED_PUT
        }

        private Mode mode = Mode.RESUMABLE;

        /**
         * Expiry for {@link Mode#SIGNED_PUT}. Ignored for resumable sessions, whose URI GCS keeps
         * valid for a week regardless.
         *
         * <p>The old fixed hour was the practical ceiling on upload size: a transfer slower than
         * that expired mid-flight, and the larger the file the likelier that became.
         */
        private int signedUrlExpiryMinutes = 60;

        /**
         * Origin to bind a resumable session to when the request carries no {@code Origin} header.
         *
         * <p>Normally unnecessary — the browser's own origin is taken from the incoming request.
         * Set it for callers that start uploads outside a web request, such as a background job.
         */
        private String browserOrigin;
    }

    /**
     * Turns uploaded video into an adaptive HLS ladder via a Cloud Run Job.
     *
     * <p>Off by default. While off, uploads and playback behave exactly as before — nothing calls
     * Cloud Run, and no credentials beyond the bucket's are needed.
     */
    @Getter
    @Setter
    public static class Transcode {

        private boolean enabled = false;

        /** Cloud Run Job to invoke. Must already exist; the library never creates it. */
        private String jobName = "transcoder";

        private String region = "europe-west1";

        /** Defaults to {@link FileStorageProperties#projectId} when blank. */
        private String projectId;

        /** Ladder output root: {@code <vodPrefix>/<group>/<id>/master.m3u8}. */
        private String vodPrefix = "vod";

        /**
         * Rungs as {@code height:videoBitrate:audioBitrate}, passed to the job verbatim.
         *
         * <p>Height applies to the SHORT side, so portrait sources keep their intended quality
         * instead of being squeezed by a landscape-shaped ladder. Rungs above the source are
         * skipped rather than upscaled.
         */
        private List<String> rungs = List.of("1080:4500k:128k", "720:2500k:128k", "360:800k:64k");

        /** Absolute URL the job posts its result to. Blank disables the callback entirely. */
        private String callbackUrl;

        /**
         * Shared secret the job presents on that callback.
         *
         * <p>The callback endpoint sits outside any JWT chain, so when this is blank it refuses
         * every request rather than defaulting to open.
         */
        private String callbackToken;

        /** Register the built-in callback endpoint. Off if the application handles it itself. */
        private boolean exposeCallbackEndpoint = true;

        private String callbackPath = "/api/internal/transcode/callback";
    }

    /** Serves an HLS ladder back out through the application, under its own access control. */
    @Getter
    @Setter
    public static class Hls {

        private boolean enabled = false;

        /**
         * Base path. The ladder-relative path follows, e.g.
         * {@code /api/media/{groupId}/hls/1080p/seg_00001.m4s}.
         */
        private String basePath = "/api/media";

        /**
         * How long an access decision is memoised, in seconds.
         *
         * <p>A playthrough is hundreds of segment requests; without this each one re-runs the
         * application's policy. Also the upper bound on how long revoked access keeps working.
         * Zero disables caching.
         */
        private long accessCacheTtlSeconds = 120;

        private int accessCacheMaxEntries = 50_000;

        private final Cdn cdn = new Cdn();

        /**
         * Serve the ladder from Cloud CDN under a signed cookie instead of through this service.
         *
         * <p>When enabled, the application authorises once and mints a cookie scoped to that one
         * ladder's path prefix; the CDN validates it at the edge and the bytes never touch the
         * application again. The origin bucket stays private — the CDN's cache-fill service account
         * reads it, nothing is granted to {@code allUsers}.
         */
        @Getter
        @Setter
        public static class Cdn {

            private boolean enabled = false;

            /** Origin the ladder is served from, e.g. {@code https://app.example.com}. */
            private String baseUrl;

            /** Key name registered on the backend bucket via {@code add-signed-url-key}. */
            private String keyName;

            /** The same key material, base64url. Keep it in a secret store, never in a repo. */
            private String keyValue;

            /**
             * Cookie lifetime. Deliberately short.
             *
             * <p>This is the revocation window: a student who loses access keeps streaming until the
             * cookie expires, because the edge cannot know anything changed. Minutes rather than the
             * hours that are common elsewhere, with the player refreshing as it plays, keeps
             * cancellations and refunds taking effect promptly. Lower it to tighten that further at
             * the cost of more refresh calls — each is a single indexed query.
             */
            private int cookieTtlSeconds = 300;
        }
    }
}
