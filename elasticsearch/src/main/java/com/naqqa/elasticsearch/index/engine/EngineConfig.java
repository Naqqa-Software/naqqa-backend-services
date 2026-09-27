package com.naqqa.elasticsearch.index.engine;

import com.naqqa.elasticsearch.common.unit.ByteSizeValue;
import com.naqqa.elasticsearch.common.unit.TimeValue;
import com.naqqa.elasticsearch.index.mapper.MapperService;
import com.naqqa.elasticsearch.index.translog.TranslogConfig;
import com.naqqa.elasticsearch.store.Directory;

import java.nio.file.Path;

public final class EngineConfig {

    private final Path shardPath;
    private final Directory directory;
    private final MapperService mapperService;
    private final TranslogConfig translogConfig;
    private final TimeValue refreshInterval;
    private final ByteSizeValue flushThresholdSize;
    private final long primaryTerm;
    private final int maxMergeAtOnce;
    private final int segmentsPerTier;
    private final double maxDeletedPctAllowed;

    public EngineConfig(Path shardPath, Directory directory, MapperService mapperService, TranslogConfig translogConfig,
                         TimeValue refreshInterval, ByteSizeValue flushThresholdSize, long primaryTerm,
                         int maxMergeAtOnce, int segmentsPerTier, double maxDeletedPctAllowed) {
        this.shardPath = shardPath;
        this.directory = directory;
        this.mapperService = mapperService;
        this.translogConfig = translogConfig;
        this.refreshInterval = refreshInterval;
        this.flushThresholdSize = flushThresholdSize;
        this.primaryTerm = primaryTerm;
        this.maxMergeAtOnce = maxMergeAtOnce;
        this.segmentsPerTier = segmentsPerTier;
        this.maxDeletedPctAllowed = maxDeletedPctAllowed;
    }

    public static EngineConfig defaultConfig(Path shardPath, Directory directory, MapperService mapperService,
                                              TranslogConfig translogConfig) {
        return new EngineConfig(shardPath, directory, mapperService, translogConfig,
            TimeValue.timeValueSeconds(1), ByteSizeValue.ofMb(512), 1L, 10, 10, 0.3);
    }

    public Path shardPath() {
        return shardPath;
    }

    public Directory directory() {
        return directory;
    }

    public MapperService mapperService() {
        return mapperService;
    }

    public TranslogConfig translogConfig() {
        return translogConfig;
    }

    public TimeValue refreshInterval() {
        return refreshInterval;
    }

    public ByteSizeValue flushThresholdSize() {
        return flushThresholdSize;
    }

    public long primaryTerm() {
        return primaryTerm;
    }

    public int maxMergeAtOnce() {
        return maxMergeAtOnce;
    }

    public int segmentsPerTier() {
        return segmentsPerTier;
    }

    public double maxDeletedPctAllowed() {
        return maxDeletedPctAllowed;
    }

    public EngineConfig withRefreshInterval(TimeValue refreshInterval) {
        return new EngineConfig(shardPath, directory, mapperService, translogConfig, refreshInterval,
            flushThresholdSize, primaryTerm, maxMergeAtOnce, segmentsPerTier, maxDeletedPctAllowed);
    }

    public EngineConfig withFlushThresholdSize(ByteSizeValue flushThresholdSize) {
        return new EngineConfig(shardPath, directory, mapperService, translogConfig, refreshInterval,
            flushThresholdSize, primaryTerm, maxMergeAtOnce, segmentsPerTier, maxDeletedPctAllowed);
    }
}
