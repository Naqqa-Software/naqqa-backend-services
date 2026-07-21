package com.naqqa.seofarm.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** A main keyword + its related sub-keywords (one topic for a blog). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SeoKeywordCluster {
    private String keyword;
    private List<String> subKeywords;
}
