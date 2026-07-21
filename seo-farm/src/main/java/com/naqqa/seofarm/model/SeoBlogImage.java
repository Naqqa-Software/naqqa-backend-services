package com.naqqa.seofarm.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** A blog image (mirrors naqqa-server's SeoBlogImage — a Pexels photo). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SeoBlogImage {
    private Long id;
    private String url;
    private String medium;
    private String small;
    private String photographer;
    private String photographerUrl;
    private String alt;
    private String pexelsUrl;
}
