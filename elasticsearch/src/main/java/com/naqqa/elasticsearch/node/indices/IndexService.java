package com.naqqa.elasticsearch.node.indices;

import com.naqqa.elasticsearch.analysis.registry.AnalysisRegistry;
import com.naqqa.elasticsearch.analysis.registry.IndexAnalyzers;
import com.naqqa.elasticsearch.cluster.state.IndexMetadata;
import com.naqqa.elasticsearch.common.unit.ByteSizeValue;
import com.naqqa.elasticsearch.common.unit.TimeValue;
import com.naqqa.elasticsearch.index.engine.EngineConfig;
import com.naqqa.elasticsearch.index.mapper.MapperService;
import com.naqqa.elasticsearch.index.shard.IndexShard;
import com.naqqa.elasticsearch.index.translog.Durability;
import com.naqqa.elasticsearch.index.translog.TranslogConfig;
import com.naqqa.elasticsearch.node.support.SettingsMaps;
import com.naqqa.elasticsearch.store.FSDirectory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class IndexService {

    private final String name;
    private final String uuid;
    private final Path indexPath;
    private final MapperService mapperService;
    private final Map<Integer, IndexShard> shards = new ConcurrentHashMap<>();
    private final Map<Integer, TranslogConfig> translogConfigs = new ConcurrentHashMap<>();
    private final long creationTimeMillis;
    private volatile IndexMetadata metadata;

    IndexService(IndexMetadata metadata, Path indexPath, AnalysisRegistry analysisRegistry) {
        this.name = metadata.getIndex();
        this.uuid = metadata.getIndexUUID();
        this.indexPath = indexPath;
        this.metadata = metadata;
        this.creationTimeMillis = metadata.getSettings().getAsLong("index.creation_date", System.currentTimeMillis());
        Map<String, Object> nestedSettings = SettingsMaps.unflatten(metadata.getSettings().getAsMap());
        IndexAnalyzers analyzers = analysisRegistry.build(nestedSettings);
        com.naqqa.elasticsearch.common.settings.Settings.Builder settings = com.naqqa.elasticsearch.common.settings.Settings.builder();
        for (Map.Entry<String, String> e : metadata.getSettings().getAsMap().entrySet()) {
            settings.put(e.getKey(), e.getValue());
        }
        this.mapperService = new MapperService(analyzers, settings.build(), name);
        this.mapperService.putMapping(metadata.getMappings() == null ? Map.of() : metadata.getMappings());
    }

    public String name() {
        return name;
    }

    public String uuid() {
        return uuid;
    }

    public Path indexPath() {
        return indexPath;
    }

    public MapperService mapperService() {
        return mapperService;
    }

    public IndexMetadata metadata() {
        return metadata;
    }

    public long creationTimeMillis() {
        return creationTimeMillis;
    }

    void updateMetadata(IndexMetadata newMetadata) {
        IndexMetadata previous = this.metadata;
        this.metadata = newMetadata;
        if (!newMetadata.getMappings().equals(previous.getMappings()) && !newMetadata.getMappings().isEmpty()) {
            mapperService.putMapping(newMetadata.getMappings());
        }
    }

    public IndexShard shard(int shardId) {
        return shards.get(shardId);
    }

    public Map<Integer, IndexShard> shards() {
        return shards;
    }

    public List<IndexShard> shardsInOrder() {
        List<IndexShard> out = new ArrayList<>();
        for (int i = 0; i < metadata.getNumberOfShards(); i++) {
            IndexShard s = shards.get(i);
            if (s != null) {
                out.add(s);
            }
        }
        return out;
    }

    public TranslogConfig translogConfig(int shardId) {
        return translogConfigs.get(shardId);
    }

    public Path shardPath(int shardId) {
        return indexPath.resolve(Integer.toString(shardId));
    }

    IndexShard openShard(int shardId) throws IOException {
        IndexShard existing = shards.get(shardId);
        if (existing != null) {
            return existing;
        }
        Path shardPath = shardPath(shardId);
        Files.createDirectories(shardPath);
        com.naqqa.elasticsearch.cluster.state.Settings s = metadata.getSettings();
        TranslogConfig translogConfig = newTranslogConfig(shardId);
        EngineConfig config = EngineConfig.defaultConfig(shardPath, new FSDirectory(shardPath.resolve("index")),
            mapperService, translogConfig);
        String refresh = s.get("index.refresh_interval");
        if (refresh != null) {
            config = config.withRefreshInterval(TimeValue.parseTimeValue(refresh, "index.refresh_interval"));
        }
        String flushThreshold = s.get("index.translog.flush_threshold_size");
        if (flushThreshold != null) {
            config = config.withFlushThresholdSize(ByteSizeValue.parseBytesSizeValue(flushThreshold, "index.translog.flush_threshold_size"));
        }
        IndexShard shard = IndexShard.open(config, mapperService);
        shards.put(shardId, shard);
        translogConfigs.put(shardId, translogConfig);
        return shard;
    }

    TranslogConfig newTranslogConfig(int shardId) {
        com.naqqa.elasticsearch.cluster.state.Settings s = metadata.getSettings();
        TranslogConfig translogConfig = TranslogConfig.defaultConfig(shardPath(shardId).resolve("translog"));
        String durability = s.get("index.translog.durability");
        if ("async".equalsIgnoreCase(durability)) {
            translogConfig = translogConfig.withDurability(Durability.ASYNC);
        }
        String syncInterval = s.get("index.translog.sync_interval");
        if (syncInterval != null) {
            translogConfig = translogConfig.withSyncInterval(TimeValue.parseTimeValue(syncInterval, "index.translog.sync_interval"));
        }
        return translogConfig;
    }

    void installShard(int shardId, IndexShard shard, TranslogConfig translogConfig) {
        IndexShard previous = shards.put(shardId, shard);
        translogConfigs.put(shardId, translogConfig);
        if (previous != null && previous != shard) {
            try {
                previous.close();
            } catch (Exception ignored) {
            }
        }
    }

    void closeShard(int shardId) {
        IndexShard shard = shards.remove(shardId);
        translogConfigs.remove(shardId);
        if (shard != null) {
            try {
                shard.flushAndClose();
            } catch (Exception e) {
                try {
                    shard.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    void close() {
        for (Integer id : new ArrayList<>(shards.keySet())) {
            closeShard(id);
        }
    }
}
