package com.naqqa.filestorage.model;

import lombok.Data;

@Data
public class FileUploadRequest {
    private String fileName;
    private String contentType;
    private String objectKey; // Received from the session initiation
    private Long size;
    private String directory;
}