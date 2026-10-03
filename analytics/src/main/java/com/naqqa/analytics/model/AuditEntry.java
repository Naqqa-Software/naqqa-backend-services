package com.naqqa.analytics.model;

import lombok.Data;
import lombok.experimental.Accessors;
import org.springframework.data.annotation.Id;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Data
@Accessors(chain = true)
public class AuditEntry {

    @Id
    private String id;
    private Instant ts;
    private String userId;
    private String action;
    private String report;
    private List<String> companyIds;
    private String impersonating;
    private Map<String, String> params;
}
