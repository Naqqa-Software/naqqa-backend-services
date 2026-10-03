package com.naqqa.analytics.model;

import lombok.Data;
import lombok.experimental.Accessors;
import org.springframework.data.annotation.Id;

import java.time.Instant;
import java.util.Map;

@Data
@Accessors(chain = true)
public class SavedView {

    @Id
    private String id;
    private String ownerId;
    private String name;
    private String section;
    private Map<String, String> query;
    private Instant createdAt;
}
