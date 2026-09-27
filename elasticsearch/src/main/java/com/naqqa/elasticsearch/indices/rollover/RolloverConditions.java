package com.naqqa.elasticsearch.indices.rollover;

import com.naqqa.elasticsearch.common.unit.ByteSizeValue;
import com.naqqa.elasticsearch.common.unit.TimeValue;

public final class RolloverConditions {

    private final TimeValue maxAge;
    private final Long maxDocs;
    private final ByteSizeValue maxSize;
    private final ByteSizeValue maxPrimaryShardSize;

    public RolloverConditions(TimeValue maxAge, Long maxDocs, ByteSizeValue maxSize, ByteSizeValue maxPrimaryShardSize) {
        this.maxAge = maxAge;
        this.maxDocs = maxDocs;
        this.maxSize = maxSize;
        this.maxPrimaryShardSize = maxPrimaryShardSize;
    }

    public TimeValue getMaxAge() {
        return maxAge;
    }

    public Long getMaxDocs() {
        return maxDocs;
    }

    public ByteSizeValue getMaxSize() {
        return maxSize;
    }

    public ByteSizeValue getMaxPrimaryShardSize() {
        return maxPrimaryShardSize;
    }

    public boolean isEmpty() {
        return maxAge == null && maxDocs == null && maxSize == null && maxPrimaryShardSize == null;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private TimeValue maxAge;
        private Long maxDocs;
        private ByteSizeValue maxSize;
        private ByteSizeValue maxPrimaryShardSize;

        public Builder maxAge(TimeValue maxAge) {
            this.maxAge = maxAge;
            return this;
        }

        public Builder maxDocs(Long maxDocs) {
            this.maxDocs = maxDocs;
            return this;
        }

        public Builder maxSize(ByteSizeValue maxSize) {
            this.maxSize = maxSize;
            return this;
        }

        public Builder maxPrimaryShardSize(ByteSizeValue maxPrimaryShardSize) {
            this.maxPrimaryShardSize = maxPrimaryShardSize;
            return this;
        }

        public RolloverConditions build() {
            return new RolloverConditions(maxAge, maxDocs, maxSize, maxPrimaryShardSize);
        }
    }
}
