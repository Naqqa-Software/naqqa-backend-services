package com.naqqa.elasticsearch.snapshots.source;

import java.util.Map;
import java.util.Set;

public interface RepositoryMetadataSource {

    Set<String> listIndices();

    Map<String, Object> indexSettings(String index);

    Map<String, Object> indexMappings(String index);

    Map<String, Object> legacyTemplates();

    Map<String, Object> indexTemplates();

    Map<String, Object> componentTemplates();

    Map<String, Object> dataStreams();
}
