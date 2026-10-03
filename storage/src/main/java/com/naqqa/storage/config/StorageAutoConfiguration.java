package com.naqqa.storage.config;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import com.naqqa.storage.NaqqaStorage;
import com.naqqa.storage.gcs.GcsStorageService;
import com.naqqa.storage.image.DefaultImageOptimizer;
import com.naqqa.storage.image.ImageOptimizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;

import java.io.IOException;
import java.io.InputStream;

/**
 * Auto-configuration for {@code naqqa-storage}. Exposes a GCS {@link Storage} client, a
 * {@link NaqqaStorage} over the configured public/private buckets, and an {@link ImageOptimizer}.
 * Every bean is {@link ConditionalOnMissingBean} so a consumer can override any of them.
 */
@AutoConfiguration
@EnableConfigurationProperties(StorageProperties.class)
public class StorageAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(StorageAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean
    public Storage naqqaStorageClient(StorageProperties props) throws IOException {
        StorageProperties.Gcs gcs = props.getGcs();
        StorageOptions.Builder builder = StorageOptions.newBuilder();
        if (gcs.getProjectId() != null && !gcs.getProjectId().isBlank()) {
            builder.setProjectId(gcs.getProjectId());
        }
        String location = gcs.getCredentialsLocation();
        if (location != null && !location.isBlank()) {
            // Never abort application startup on a bad key path: log and fall back to ADC. A missing key
            // then fails only the actual GCS calls, instead of crash-looping the whole service.
            try {
                Resource resource = new DefaultResourceLoader().getResource(location);
                try (InputStream in = resource.getInputStream()) {
                    builder.setCredentials(GoogleCredentials.fromStream(in));
                }
                log.info("naqqa-storage: GCS credentials loaded from {}", location);
            } catch (Exception e) {
                log.error("naqqa-storage: could NOT load GCS credentials from {} ({}); falling back to "
                        + "Application Default Credentials. GCS operations will fail until this is fixed.", location, e.getMessage());
            }
        } else {
            log.info("naqqa-storage: no credentials-location set; using Application Default Credentials");
        }
        return builder.build().getService();
    }

    @Bean
    @ConditionalOnMissingBean
    public NaqqaStorage naqqaStorage(Storage storage, StorageProperties props) {
        return new GcsStorageService(storage, props.getGcs());
    }

    @Bean
    @ConditionalOnMissingBean
    public ImageOptimizer naqqaImageOptimizer(StorageProperties props) {
        return new DefaultImageOptimizer(props.getImage());
    }
}
