package com.naqqa.chat.model;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class MediaUploadResponse {
    private String url;
    private String mediaType;
}
