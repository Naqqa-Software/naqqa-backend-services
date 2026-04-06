package com.naqqa.filestorage.config;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import com.naqqa.filestorage.repository.FileRepository;
import com.naqqa.filestorage.service.FileStorageService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
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
        try (InputStream inputStream = new ClassPathResource(props.getConfigFile()).getInputStream()) {
            return StorageOptions.newBuilder()
                    .setProjectId(props.getProjectId())
                    .setCredentials(GoogleCredentials.fromStream(inputStream))
                    .build()
                    .getService();
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize GCS Storage", e);
        }
    }

    @Bean
    @ConditionalOnMissingBean
    public FileStorageService fileStorageService(
            FileRepository fileRepository,
            Storage storage,
            FileStorageProperties props
    ) {
        return new FileStorageService(fileRepository, storage, props);
    }
}
