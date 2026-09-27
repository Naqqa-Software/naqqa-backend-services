package com.naqqa.elasticsearch.snapshots.model;

public record ShardId(String index, int shard) implements Comparable<ShardId> {

    public String key() {
        return index + "/" + shard;
    }

    public static ShardId parseKey(String key) {
        int slash = key.lastIndexOf('/');
        return new ShardId(key.substring(0, slash), Integer.parseInt(key.substring(slash + 1)));
    }

    @Override
    public int compareTo(ShardId o) {
        int c = index.compareTo(o.index);
        if (c != 0) {
            return c;
        }
        return Integer.compare(shard, o.shard);
    }

    @Override
    public String toString() {
        return key();
    }
}
