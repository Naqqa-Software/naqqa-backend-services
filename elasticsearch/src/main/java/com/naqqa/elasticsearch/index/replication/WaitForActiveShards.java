package com.naqqa.elasticsearch.index.replication;

public final class WaitForActiveShards {

    public static final WaitForActiveShards ALL = new WaitForActiveShards(-1, true);
    public static final WaitForActiveShards DEFAULT = ALL;
    public static final WaitForActiveShards NONE = new WaitForActiveShards(1, false);

    private final int totalCopiesRequired;
    private final boolean all;

    private WaitForActiveShards(int totalCopiesRequired, boolean all) {
        this.totalCopiesRequired = totalCopiesRequired;
        this.all = all;
    }

    public static WaitForActiveShards of(int totalCopiesIncludingPrimary) {
        if (totalCopiesIncludingPrimary < 0) {
            throw new IllegalArgumentException("wait_for_active_shards must be >= 0, got [" + totalCopiesIncludingPrimary + "]");
        }
        return new WaitForActiveShards(totalCopiesIncludingPrimary, false);
    }

    public static WaitForActiveShards parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT;
        }
        String value = raw.trim();
        if (value.equalsIgnoreCase("all")) {
            return ALL;
        }
        try {
            int parsed = Integer.parseInt(value);
            return of(parsed);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("invalid wait_for_active_shards value [" + raw
                + "], expected \"all\" or a non-negative number");
        }
    }

    public boolean isAll() {
        return all;
    }

    public int requiredReplicaAcks(int currentInSyncReplicaCount) {
        if (all) {
            return currentInSyncReplicaCount;
        }
        int replicasRequired = totalCopiesRequired - 1;
        if (replicasRequired < 0) {
            replicasRequired = 0;
        }
        return Math.min(replicasRequired, currentInSyncReplicaCount);
    }

    @Override
    public String toString() {
        return all ? "ALL" : Integer.toString(totalCopiesRequired);
    }
}
