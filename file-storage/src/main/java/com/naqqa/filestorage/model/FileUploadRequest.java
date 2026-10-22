package com.naqqa.filestorage.model;

import lombok.Data;

@Data
public class FileUploadRequest {
    private String fileName;
    private String contentType;
    private String objectKey;
    private Long size;
    private String directory;
}