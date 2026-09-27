package com.naqqa.elasticsearch.index.translog;

import com.naqqa.elasticsearch.common.unit.ByteSizeValue;
import com.naqqa.elasticsearch.common.unit.TimeValue;

import java.nio.file.Path;

public final class TranslogConfig {

    private final Path translogPath;
    private final ByteSizeValue generationThresholdSize;
    private final TimeValue maxGenerationAge;
    private final Durability durability;
    private final TimeValue syncInterval;
    private final String translogUUID;

    public TranslogConfig(Path translogPath, ByteSizeValue generationThresholdSize, TimeValue maxGenerationAge,
                           Durability durability, TimeValue syncInterval, String translogUUID) {
        this.translogPath = translogPath;
        this.generationThresholdSize = generationThresholdSize;
        this.maxGenerationAge = maxGenerationAge;
        this.durability = durability;
        this.syncInterval = syncInterval;
        this.translogUUID = translogUUID;
    }

    public static TranslogConfig defaultConfig(Path path) {
        return new TranslogConfig(path, ByteSizeValue.ofMb(64), TimeValue.MINUS_ONE, Durability.REQUEST, TimeValue.timeValueSeconds(5), null);
    }

    public Path translogPath() {
        return translogPath;
    }

    public ByteSizeValue generationThresholdSize() {
        return generationThresholdSize;
    }

    public TimeValue maxGenerationAge() {
        return maxGenerationAge;
    }

    public Durability durability() {
        return durability;
    }

    public TimeValue syncInterval() {
        return syncInterval;
    }

    public String translogUUID() {
        return translogUUID;
    }

    public TranslogConfig withGenerationThresholdSize(ByteSizeValue size) {
        return new TranslogConfig(translogPath, size, maxGenerationAge, durability, syncInterval, translogUUID);
    }

    public TranslogConfig withMaxGenerationAge(TimeValue age) {
        return new TranslogConfig(translogPath, generationThresholdSize, age, durability, syncInterval, translogUUID);
    }

    public TranslogConfig withDurability(Durability durability) {
        return new TranslogConfig(translogPath, generationThresholdSize, maxGenerationAge, durability, syncInterval, translogUUID);
    }

    public TranslogConfig withSyncInterval(TimeValue syncInterval) {
        return new TranslogConfig(translogPath, generationThresholdSize, maxGenerationAge, durability, syncInterval, translogUUID);
    }
}
