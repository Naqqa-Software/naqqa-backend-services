package com.naqqa.sso.ratelimit;

public record FailureState(int count, long lastFailureMillis) {
}
