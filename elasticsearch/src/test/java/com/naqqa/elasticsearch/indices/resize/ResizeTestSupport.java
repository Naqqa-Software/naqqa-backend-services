package com.naqqa.elasticsearch.indices.resize;

import com.naqqa.elasticsearch.analysis.registry.AnalysisRegistry;
import com.naqqa.elasticsearch.analysis.registry.IndexAnalyzers;
import com.naqqa.elasticsearch.common.settings.Settings;
import com.naqqa.elasticsearch.common.unit.TimeValue;
import com.naqqa.elasticsearch.index.engine.EngineConfig;
import com.naqqa.elasticsearch.index.mapper.MapperService;
import com.naqqa.elasticsearch.index.shard.IndexShard;
import com.naqqa.elasticsearch.index.translog.TranslogConfig;
import com.naqqa.elasticsearch.store.Directory;
import com.naqqa.elasticsearch.store.FSDirectory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

final class ResizeTestSupport {

    private ResizeTestSupport() {
    }

    static MapperService newMapperService() {
        IndexAnalyzers analyzers = new AnalysisRegistry().build(Map.of());
        MapperService ms = new MapperService(analyzers, Settings.EMPTY, "resize-test-index");
        ms.putMapping(Map.of("properties", Map.of(
            "title", Map.of("type", "text"),
            "tag", Map.of("type", "keyword"))));
        return ms;
    }

    static Path newTempDir(String prefix) throws IOException {
        return Files.createTempDirectory(prefix);
    }

    static IndexShard openSourceShard(Path shardPath, MapperService mapperService) throws IOException {
        Directory directory = new FSDirectory(shardPath.resolve("index"));
        TranslogConfig translogConfig = TranslogConfig.defaultConfig(shardPath.resolve("translog"));
        EngineConfig config = EngineConfig.defaultConfig(shardPath, directory, mapperService, translogConfig)
            .withRefreshInterval(TimeValue.MINUS_ONE);
        return IndexShard.open(config, mapperService);
    }

    static IndexShard openTargetShard(Path shardPath, MapperService mapperService) throws IOException {
        return IndexShard.open(shardPath, mapperService);
    }
}
