package com.naqqa.elasticsearch.transport;

public final class TransportVersion {

    private TransportVersion() {
    }

    public static final int V_1 = 1;
    public static final int CURRENT = V_1;
    public static final int MIN_COMPATIBLE = V_1;

    public static boolean isCompatible(int version) {
        return version >= MIN_COMPATIBLE && version <= CURRENT;
    }
}
