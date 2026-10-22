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

@Configuration
@EnableConfigurationProperties(FileStorageProperties.class)
public class FileStorageAutoConfiguration {

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

    @Bean
    @ConditionalOnMissingBean
    public CdnSignedCookieService cdnSignedCookieService(FileStorageProperties props) {
        return new CdnSignedCookieService(props);
    }
}
