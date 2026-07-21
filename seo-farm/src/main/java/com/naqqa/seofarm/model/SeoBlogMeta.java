package com.naqqa.seofarm.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** SEO metadata embedded in a blog (mirrors naqqa-server's SeoBlogMeta). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SeoBlogMeta {
    private String title;
    private String description;
    private List<String> keywords;
    private List<String> tags;
    private String language;
    private String country;
}
