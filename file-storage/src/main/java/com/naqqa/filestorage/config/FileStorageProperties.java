package com.naqqa.filestorage.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "gcs.lib")
public class FileStorageProperties {

    private String configFile;
    private String projectId;
    private String bucketName;

}
