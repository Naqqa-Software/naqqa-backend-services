package com.naqqa.filestorage.config;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import com.naqqa.filestorage.repository.FileRepository;
import com.naqqa.filestorage.service.FileStorageService;
import com.naqqa.filestorage.service.RoutingFileStorageService;
import com.naqqa.filestorage.video.CdnSignedCookieService;
import com.naqqa.filestorage.video.VideoTranscodeService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

import java.io.InputStream;

/**
 * Wires the library from {@link FileStorageProperties}.
 *
 * <p>Every bean is {@link ConditionalOnMissingBean}, so an application can replace any piece by
 * declaring its own. The optional features are additionally gated on configuration and, where they
 * need application input, on the presence of the interface bean that supplies it — so nothing is
 * registered that could not work.
 */
@Configuration
@EnableConfigurationProperties(FileStorageProperties.class)
public class FileStorageAutoConfiguration {

    /**
     * GCS client.
     *
     * <p>With {@code config-file} blank this uses Application Default Credentials, which is what a
     * workload running on GCP should do — no key material to ship or rotate. A classpath key is
     * supported for local development and emulators.
     */
    @Bean
    @ConditionalOnMissingBean
    public Storage storage(FileStorageProperties props) {
        try {
            StorageOptions.Builder builder = StorageOptions.newBuilder()
                    .setProjectId(props.getProjectId());
            if (props.getConfigFile() != null && !props.getConfigFile().isBlank()) {
                try (InputStream in = new ClassPathResource(props.getConfigFile()).getInputStream()) {
                    builder.setCredentials(GoogleCredentials.fromStream(in));
                }
            }
            return builder.build().getService();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to initialize GCS Storage", e);
        }
    }

    /**
     * The storage service.
     *
     * <p>Always the routing implementation: with no public bucket and unique names disabled it is a
     * pass-through, so there is no behavioural reason to register the plain one instead — and this
     * way turning either feature on is a property change rather than a code change.
     */
    @Bean
    @ConditionalOnMissingBean
    public FileStorageService fileStorageService(FileRepository fileRepository, Storage storage,
                                                 FileStorageProperties props) {
        return new RoutingFileStorageService(fileRepository, storage, props);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "gcs.lib.transcode", name = "enabled", havingValue = "true")
    public VideoTranscodeService videoTranscodeService(FileStorageProperties props) {
        return new VideoTranscodeService(props);
    }

    /**
     * Always registered, even with the CDN switched off — the application asks whether it is
     * configured and serves through itself when it is not, so the switch is a property change
     * rather than a deploy.
     */
    @Bean
    @ConditionalOnMissingBean
    public CdnSignedCookieService cdnSignedCookieService(FileStorageProperties props) {
        return new CdnSignedCookieService(props);
    }
}
