package com.naqqa.elasticsearch.index.mapper;

import com.naqqa.elasticsearch.analysis.registry.AnalysisRegistry;
import com.naqqa.elasticsearch.analysis.registry.IndexAnalyzers;
import com.naqqa.elasticsearch.common.settings.Settings;

import java.util.Map;

final class MapperServiceTestSupport {

    private MapperServiceTestSupport() {
    }

    static MapperService newMapperService() {
        return newMapperService(Settings.EMPTY);
    }

    static MapperService newMapperService(Settings extraSettings) {
        IndexAnalyzers analyzers = new AnalysisRegistry().build(Map.of());
        return new MapperService(analyzers, extraSettings, "test-index");
    }
}
