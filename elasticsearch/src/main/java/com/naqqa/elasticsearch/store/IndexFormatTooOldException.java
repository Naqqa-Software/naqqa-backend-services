package com.naqqa.elasticsearch.store;

import java.io.IOException;

public class IndexFormatTooOldException extends IOException {

    private final String resourceDescription;
    private final int version;
    private final int minVersion;
    private final int maxVersion;

    public IndexFormatTooOldException(String resourceDescription, int version, int minVersion, int maxVersion) {
        super("Format version is not supported (resource " + resourceDescription + "): " + version
            + " (needs to be between " + minVersion + " and " + maxVersion + "). This version of the index is too old.");
        this.resourceDescription = resourceDescription;
        this.version = version;
        this.minVersion = minVersion;
        this.maxVersion = maxVersion;
    }

    public IndexFormatTooOldException(DataInput in, int version, int minVersion, int maxVersion) {
        this(String.valueOf(in), version, minVersion, maxVersion);
    }

    public String getResourceDescription() {
        return resourceDescription;
    }

    public int getVersion() {
        return version;
    }

    public int getMinVersion() {
        return minVersion;
    }

    public int getMaxVersion() {
        return maxVersion;
    }
}
