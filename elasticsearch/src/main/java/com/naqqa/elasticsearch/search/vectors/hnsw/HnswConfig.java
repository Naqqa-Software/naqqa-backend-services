package com.naqqa.elasticsearch.search.vectors.hnsw;

public record HnswConfig(int m, int efConstruction, long seed) {

    public static final int DEFAULT_M = 16;
    public static final int DEFAULT_EF_CONSTRUCTION = 100;
    public static final long DEFAULT_SEED = 42L;
    public static final int MAX_M = 512;
    public static final int MAX_EF_CONSTRUCTION = 3200;

    public HnswConfig {
        if (m < 2 || m > MAX_M) {
            throw new IllegalArgumentException("m must be in [2, " + MAX_M + "], got " + m);
        }
        if (efConstruction < 1 || efConstruction > MAX_EF_CONSTRUCTION) {
            throw new IllegalArgumentException("ef_construction must be in [1, " + MAX_EF_CONSTRUCTION + "], got " + efConstruction);
        }
    }

    public HnswConfig(int m, int efConstruction) {
        this(m, efConstruction, DEFAULT_SEED);
    }

    public static HnswConfig defaults() {
        return new HnswConfig(DEFAULT_M, DEFAULT_EF_CONSTRUCTION, DEFAULT_SEED);
    }
}
