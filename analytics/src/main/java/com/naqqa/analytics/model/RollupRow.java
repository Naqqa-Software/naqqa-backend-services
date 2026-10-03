package com.naqqa.analytics.model;

import lombok.Data;
import lombok.experimental.Accessors;
import org.springframework.data.annotation.Id;

import java.time.Instant;
import java.util.Map;

@Data
@Accessors(chain = true)
public class RollupRow {

    @Id
    private String id;
    private String day;
    private String metric;
    private Map<String, String> dims;
    private long count;
    private long uniques;
    private long activeMs;
    private Instant updatedAt;
}
