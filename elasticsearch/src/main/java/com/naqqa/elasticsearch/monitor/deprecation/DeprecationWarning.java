package com.naqqa.elasticsearch.monitor.deprecation;

import java.time.Instant;

public record DeprecationWarning(String key, String message, Instant timestamp) {
}
