package com.naqqa.elasticsearch.node.indices;

import com.naqqa.elasticsearch.cluster.state.AliasMetadata;
import com.naqqa.elasticsearch.cluster.state.IndexMetadata;
import com.naqqa.elasticsearch.index.shard.IndexShard;
import com.naqqa.elasticsearch.indices.ilm.IndexLifecycleActionExecutor;
import com.naqqa.elasticsearch.node.action.NodeIndexAdminActionService;
import com.naqqa.elasticsearch.rest.support.IndexNotFoundException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class NodeLifecycleActionExecutor implements IndexLifecycleActionExecutor {

    public static final String ROLLOVER_ALIAS_SETTING = "index.lifecycle.rollover_alias";

    private final MetadataIndexService metadataService;
    private final IndicesService indicesService;
    private final NodeIndexAdminActionService indexAdmin;

    public NodeLifecycleActionExecutor(MetadataIndexService metadataService, IndicesService indicesService,
                                       NodeIndexAdminActionService indexAdmin) {
        this.metadataService = metadataService;
        this.indicesService = indicesService;
        this.indexAdmin = indexAdmin;
    }

    private IndexMetadata require(String index) {
        IndexMetadata imd = metadataService.state().getMetadata().index(index);
        if (imd == null) {
            throw new IndexNotFoundException(index);
        }
        return imd;
    }

    @Override
    public String rolloverIndex(String index) {
        IndexMetadata imd = require(index);
        for (String ds : indexAdmin.dataStreamNames()) {
            List<String> backing = indexAdmin.dataStreamBackingIndices(ds);
            if (!backing.isEmpty() && backing.get(backing.size() - 1).equals(index)) {
                return String.valueOf(indexAdmin.rolloverInternal(ds, null, Map.of(), false).get("new_index"));
            }
        }
        String alias = imd.getSettings().get(ROLLOVER_ALIAS_SETTING);
        if (alias == null) {
            for (AliasMetadata a : imd.getAliases().values()) {
                if (a.isWriteIndex() || metadataService.state().getMetadata().resolveIndicesForAlias(a.getAlias()).size() == 1) {
                    alias = a.getAlias();
                    break;
                }
            }
        }
        if (alias == null) {
            throw new IllegalStateException("index [" + index + "] has no rollover alias configured via [" + ROLLOVER_ALIAS_SETTING + "]");
        }
        String writeIndex = metadataService.aliasService().resolveWriteIndex(alias);
        if (writeIndex != null && !writeIndex.equals(index)) {
            return writeIndex;
        }
        return String.valueOf(indexAdmin.rolloverInternal(alias, null, Map.of(), false).get("new_index"));
    }

    @Override
    public String shrinkIndex(String index, int numberOfShards) {
        require(index);
        return indexAdmin.shrinkForIlm(index, numberOfShards);
    }

    @Override
    public void forceMergeIndex(String index, int maxNumSegments) {
        require(index);
        for (IndexShard shard : indicesService.localShards(index)) {
            try {
                shard.forceMerge(Math.max(1, maxNumSegments));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    @Override
    public void setIndexReadOnly(String index, boolean readOnly) {
        require(index);
        metadataService.putSettings(List.of(index), Map.of("index.blocks.write", Boolean.toString(readOnly)));
    }

    @Override
    public void allocateIndex(String index, Map<String, String> routingSettings, Integer numberOfReplicas) {
        require(index);
        Map<String, String> settings = new LinkedHashMap<>();
        if (routingSettings != null) {
            for (Map.Entry<String, String> e : routingSettings.entrySet()) {
                String key = e.getKey().startsWith("index.") ? e.getKey() : "index.routing.allocation." + e.getKey();
                settings.put(key, e.getValue());
            }
        }
        if (numberOfReplicas != null) {
            settings.put("index.number_of_replicas", Integer.toString(numberOfReplicas));
        }
        if (!settings.isEmpty()) {
            metadataService.putSettings(List.of(index), settings);
        }
    }

    @Override
    public void deleteIndex(String index) {
        if (metadataService.state().getMetadata().index(index) == null) {
            return;
        }
        metadataService.deleteIndices(List.of(index));
    }

    @Override
    public void setIndexPriority(String index, int priority) {
        require(index);
        metadataService.putSettings(List.of(index), Map.of("index.priority", Integer.toString(priority)));
    }
}
