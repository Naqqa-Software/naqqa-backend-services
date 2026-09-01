package com.naqqa.filestorage.video;

import com.google.auth.oauth2.GoogleCredentials;
import com.naqqa.filestorage.config.FileStorageProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Starts a Cloud Run Job that turns an uploaded master into an adaptive HLS ladder.
 *
 * <p>Invoked over the Run Admin REST API with per-execution container overrides rather than through
 * Cloud Tasks or Pub/Sub: Cloud Run Jobs already provide retries, per-execution logs and a
 * concurrency cap, and this needs no extra client library — it is one authenticated POST using the
 * same Application Default Credentials the Storage client relies on.
 *
 * <p><b>Never throws.</b> A failed submit returns false and logs; it must not fail the caller's
 * upload. A video that costs more to serve is a far better outcome than an upload that appears to
 * break. Callers should record the un-started state and retry on a timer — nothing re-tries here.
 *
 * <p><b>The job must already exist</b> and grant the caller's service account
 * {@code run.jobs.runWithOverrides}. That is a distinct permission from {@code run.jobs.run}:
 * {@code roles/run.invoker} grants the latter but NOT the former, so an invoker-only binding fails
 * at runtime with 403 while looking correct.
 */
public class VideoTranscodeService {

    private static final Logger log = LoggerFactory.getLogger(VideoTranscodeService.class);

    private final FileStorageProperties props;
    private final RestClient restClient = RestClient.create();

    public VideoTranscodeService(FileStorageProperties props) {
        this.props = props;
    }

    /**
     * The bucket prefix a ladder lives under: {@code <vodPrefix>/<groupId>}.
     *
     * <p>Derived rather than stored, so a future CDN can be pointed at a path prefix without any
     * object having to move.
     */
    public String ladderPrefix(String groupId) {
        return props.getTranscode().getVodPrefix() + "/" + groupId;
    }

    /** Master playlist object key for a ladder. */
    public String masterPlaylistKey(String groupId) {
        return ladderPrefix(groupId) + "/master.m3u8";
    }

    /**
     * Submits a transcode.
     *
     * @param masterObjectKey key of the uploaded master, within the configured bucket
     * @param groupId         identifies this ladder; also the correlation id on the callback
     * @return true if the job execution was accepted
     */
    public boolean submit(String masterObjectKey, String groupId) {
        FileStorageProperties.Transcode cfg = props.getTranscode();
        if (!cfg.isEnabled()) {
            log.debug("Transcoding disabled; not submitting '{}'", groupId);
            return false;
        }
        if (masterObjectKey == null || masterObjectKey.isBlank() || groupId == null || groupId.isBlank()) {
            log.warn("Refusing to submit transcode with blank master key or group id");
            return false;
        }

        String bucket = props.getBucketName();
        String project = (cfg.getProjectId() == null || cfg.getProjectId().isBlank())
                ? props.getProjectId() : cfg.getProjectId();

        Map<String, String> env = new LinkedHashMap<>();
        env.put("MASTER_URI", "gs://%s/%s".formatted(bucket, masterObjectKey));
        env.put("OUTPUT_PREFIX", "gs://%s/%s".formatted(bucket, ladderPrefix(groupId)));
        env.put("GROUP_ID", groupId);
        env.put("CALLBACK_URL", cfg.getCallbackUrl());
        env.put("CALLBACK_TOKEN", cfg.getCallbackToken());
        if (cfg.getRungs() != null && !cfg.getRungs().isEmpty()) {
            env.put("RUNGS", String.join(" ", cfg.getRungs()));
        }

        try {
            runJob(project, cfg, env);
            log.info("Transcode submitted for '{}' -> {}", groupId, ladderPrefix(groupId));
            return true;
        } catch (Exception e) {
            log.error("Could not start transcode for '{}': {}", groupId, e.getMessage());
            return false;
        }
    }

    private void runJob(String project, FileStorageProperties.Transcode cfg,
                        Map<String, String> env) throws Exception {
        GoogleCredentials creds = GoogleCredentials.getApplicationDefault()
                .createScoped("https://www.googleapis.com/auth/cloud-platform");
        creds.refreshIfExpired();

        List<Map<String, String>> envList = new ArrayList<>();
        env.forEach((k, v) -> {
            if (v != null && !v.isBlank()) {
                envList.add(Map.of("name", k, "value", v));
            }
        });

        String url = "https://run.googleapis.com/v2/projects/%s/locations/%s/jobs/%s:run"
                .formatted(project, cfg.getRegion(), cfg.getJobName());

        restClient.post()
                .uri(url)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + creds.getAccessToken().getTokenValue())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("overrides", Map.of("containerOverrides", List.of(Map.of("env", envList)))))
                .retrieve()
                .toBodilessEntity();
    }
}
