package com.naqqa.filestorage.model;

import lombok.Data;

@Data
public class FinalizeUploadRequest {
    private Long fileId;
    private Long size;
}