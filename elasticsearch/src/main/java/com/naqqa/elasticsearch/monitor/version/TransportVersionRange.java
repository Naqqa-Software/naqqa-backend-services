package com.naqqa.elasticsearch.monitor.version;

public record TransportVersionRange(int minSupported, int maxSupported) {

    public boolean isCompatible(int version) {
        return version >= minSupported && version <= maxSupported;
    }
}
