package com.naqqa.analytics.model;

import lombok.Data;
import lombok.experimental.Accessors;
import org.springframework.data.annotation.Id;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Data
@Accessors(chain = true)
public class ScheduledReport {

    @Id
    private String id;
    private String ownerId;
    private String name;
    private String report;
    private String format;
    private String frequency;
    private List<String> recipients;
    private Map<String, String> query;
    private String lang;
    private boolean partner;
    private Instant nextRunAt;
    private Instant lastRunAt;
    private Instant createdAt;
}
