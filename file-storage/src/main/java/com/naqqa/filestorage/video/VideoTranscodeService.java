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

public class VideoTranscodeService {

    private static final Logger log = LoggerFactory.getLogger(VideoTranscodeService.class);

    private final FileStorageProperties props;
    private final RestClient restClient = RestClient.create();

    public VideoTranscodeService(FileStorageProperties props) {
        this.props = props;
    }

    public String ladderPrefix(String groupId) {
        return props.getTranscode().getVodPrefix() + "/" + groupId;
    }

    public String masterPlaylistKey(String groupId) {
        return ladderPrefix(groupId) + "/master.m3u8";
    }

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
