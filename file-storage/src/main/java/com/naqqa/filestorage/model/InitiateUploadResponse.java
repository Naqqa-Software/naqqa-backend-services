package com.naqqa.filestorage.model;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class InitiateUploadResponse {
    private String uploadUrl;
    private Long fileId;
}