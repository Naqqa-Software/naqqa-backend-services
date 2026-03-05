package com.naqqa.entity.dto;

import java.time.Instant;
import java.util.Map;

public record EntityRecordResponse(
        String id,
        String entityKey,
        Map<String, Object> data,
        Instant createdAt,
        Instant updatedAt
) {
}
