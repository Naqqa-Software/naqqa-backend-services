package com.naqqa.elasticsearch.index.engine;

import com.naqqa.elasticsearch.analysis.registry.AnalysisRegistry;
import com.naqqa.elasticsearch.analysis.registry.IndexAnalyzers;
import com.naqqa.elasticsearch.common.settings.Settings;
import com.naqqa.elasticsearch.common.unit.TimeValue;
import com.naqqa.elasticsearch.index.mapper.MapperService;
import com.naqqa.elasticsearch.index.translog.TranslogConfig;
import com.naqqa.elasticsearch.store.Directory;
import com.naqqa.elasticsearch.store.FSDirectory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

final class EngineTestSupport {

    private EngineTestSupport() {
    }

    static MapperService newMapperService() {
        IndexAnalyzers analyzers = new AnalysisRegistry().build(Map.of());
        MapperService ms = new MapperService(analyzers, Settings.EMPTY, "test-index");
        ms.putMapping(Map.of("properties", Map.of(
            "title", Map.of("type", "text"),
            "tag", Map.of("type", "keyword"))));
        return ms;
    }

    static Path newTempShardPath() throws IOException {
        return Files.createTempDirectory("engine-test");
    }

    static EngineConfig newConfig(Path shardPath, MapperService mapperService) throws IOException {
        Directory directory = new FSDirectory(shardPath.resolve("index"));
        TranslogConfig translogConfig = TranslogConfig.defaultConfig(shardPath.resolve("translog"));
        return EngineConfig.defaultConfig(shardPath, directory, mapperService, translogConfig)
            .withRefreshInterval(TimeValue.MINUS_ONE);
    }

    static InternalEngine open(Path shardPath, MapperService mapperService) throws IOException {
        return InternalEngine.open(newConfig(shardPath, mapperService));
    }
}
