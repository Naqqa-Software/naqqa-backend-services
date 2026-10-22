package com.naqqa.sso.ratelimit;

public record Limit(boolean allowed, long remaining, long retryAfterMillis) {
}
