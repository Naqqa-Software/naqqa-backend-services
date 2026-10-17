package com.naqqa.sso.ratelimit;

public record BucketState(double tokens, long updatedMillis) {
}
