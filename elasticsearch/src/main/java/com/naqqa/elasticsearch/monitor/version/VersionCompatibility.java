package com.naqqa.elasticsearch.monitor.version;

import java.util.Optional;

public final class VersionCompatibility {

    private VersionCompatibility() {
    }

    public enum IndexCompatibility {
        READ_WRITE,
        READ_ONLY,
        UNSUPPORTED
    }

    public static Optional<Integer> negotiate(TransportVersionRange local, TransportVersionRange remote) {
        int min = Math.max(local.minSupported(), remote.minSupported());
        int max = Math.min(local.maxSupported(), remote.maxSupported());
        if (min > max) {
            return Optional.empty();
        }
        return Optional.of(max);
    }

    public static IndexCompatibility indexCompatibility(int currentNodeMajorVersion, int indexCreatedMajorVersion) {
        if (indexCreatedMajorVersion > currentNodeMajorVersion) {
            return IndexCompatibility.UNSUPPORTED;
        }
        int majorVersionDiff = currentNodeMajorVersion - indexCreatedMajorVersion;
        if (majorVersionDiff == 0) {
            return IndexCompatibility.READ_WRITE;
        }
        if (majorVersionDiff == 1) {
            return IndexCompatibility.READ_ONLY;
        }
        return IndexCompatibility.UNSUPPORTED;
    }
}
