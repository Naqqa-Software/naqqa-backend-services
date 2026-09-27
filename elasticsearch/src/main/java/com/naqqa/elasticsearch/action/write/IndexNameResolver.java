package com.naqqa.elasticsearch.action.write;

import com.naqqa.elasticsearch.cluster.state.AliasMetadata;
import com.naqqa.elasticsearch.cluster.state.ClusterState;
import com.naqqa.elasticsearch.cluster.state.IndexMetadata;
import com.naqqa.elasticsearch.cluster.state.Metadata;
import com.naqqa.elasticsearch.rest.support.IndexNotFoundException;
import com.naqqa.elasticsearch.rest.support.RestApiException;

import java.util.Set;

public final class IndexNameResolver {

    public record Resolution(String index, String routingOverride) {
    }

    private IndexNameResolver() {
    }

    public static Resolution resolveForWrite(ClusterState state, String indexOrAlias) {
        Metadata metadata = state.getMetadata();
        IndexMetadata direct = metadata.index(indexOrAlias);
        if (direct != null) {
            return new Resolution(indexOrAlias, null);
        }
        Set<String> candidates = metadata.resolveIndicesForAlias(indexOrAlias);
        if (candidates.isEmpty()) {
            throw new IndexNotFoundException(indexOrAlias);
        }
        if (candidates.size() == 1) {
            String only = candidates.iterator().next();
            AliasMetadata aliasMetadata = metadata.index(only).getAliases().get(indexOrAlias);
            String routing = aliasMetadata != null ? aliasMetadata.getIndexRouting() : null;
            return new Resolution(only, routing);
        }
        String writeIndex = null;
        String writeRouting = null;
        for (String candidate : candidates) {
            AliasMetadata aliasMetadata = metadata.index(candidate).getAliases().get(indexOrAlias);
            if (aliasMetadata != null && aliasMetadata.isWriteIndex()) {
                if (writeIndex != null) {
                    throw new RestApiException(400, "alias [" + indexOrAlias
                        + "] has more than one write index [" + writeIndex + "," + candidate + "]");
                }
                writeIndex = candidate;
                writeRouting = aliasMetadata.getIndexRouting();
            }
        }
        if (writeIndex == null) {
            throw new RestApiException(400, "no write index is defined for alias [" + indexOrAlias
                + "]. The write index may be explicitly disabled using is_write_index=false or the alias points "
                + "to multiple indices without one being designated as a write index");
        }
        return new Resolution(writeIndex, writeRouting);
    }
}
