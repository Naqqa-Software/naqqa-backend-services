package com.naqqa.elasticsearch.node.search;

import com.naqqa.elasticsearch.action.search.SegmentOwnership;
import com.naqqa.elasticsearch.cluster.routing.ShardId;
import com.naqqa.elasticsearch.index.engine.EngineSearcher;
import com.naqqa.elasticsearch.index.engine.segment.SegmentReader;
import com.naqqa.elasticsearch.index.engine.segment.StoredDocCodec;
import com.naqqa.elasticsearch.node.indices.IndexService;
import com.naqqa.elasticsearch.search.advanced.common.SegmentReaderLeafAdapter;
import com.naqqa.elasticsearch.search.execution.IndexSearcher;
import com.naqqa.elasticsearch.search.execution.LeafReader;
import com.naqqa.elasticsearch.search.execution.LeafReaderContext;
import com.naqqa.elasticsearch.search.execution.SearchExecutors;
import com.naqqa.elasticsearch.search.query.Query;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

final class ShardTarget {

    final int ordinal;
    final ShardId shardId;
    final IndexService indexService;
    final IndexSearcher searcher;
    final List<SegmentReader> segments = new ArrayList<>();
    final Function<String, QueryFactory.FieldType> fieldTypes;
    private final Map<String, Optional<QueryFactory.FieldType>> typeCache = new HashMap<>();
    Query query;
    float indexBoost = 1f;

    ShardTarget(int ordinal, ShardId shardId, EngineSearcher engineSearcher, IndexService indexService,
                Function<String, QueryFactory.FieldType> fieldTypes) {
        this.ordinal = ordinal;
        this.shardId = shardId;
        this.indexService = indexService;
        this.fieldTypes = fieldTypes;
        SegmentOwnership.register(engineSearcher, shardId.index());
        List<LeafReader> leaves = new ArrayList<>();
        for (SegmentReader sr : engineSearcher.leaves()) {
            segments.add(sr);
            leaves.add(new SegmentReaderLeafAdapter(sr));
        }
        this.searcher = new IndexSearcher(leaves, SearchExecutors.shared());
    }

    String index() {
        return shardId.index();
    }

    QueryFactory.FieldType fieldType(String field) {
        return typeCache.computeIfAbsent(field, f -> Optional.ofNullable(fieldTypes.apply(f))).orElse(null);
    }

    LeafReaderContext leafFor(int doc) {
        List<LeafReaderContext> leaves = searcher.leafContexts();
        for (int i = leaves.size() - 1; i >= 0; i--) {
            LeafReaderContext ctx = leaves.get(i);
            if (doc >= ctx.docBase() && doc < ctx.docBase() + ctx.reader().maxDoc()) {
                return ctx;
            }
        }
        return null;
    }

    StoredDocCodec.Decoded fetch(int doc) throws IOException {
        LeafReaderContext ctx = leafFor(doc);
        if (ctx == null) {
            return null;
        }
        return segments.get(ctx.ord()).storedDocument(doc - ctx.docBase());
    }

    List<Object> docValues(String field, int doc) throws IOException {
        LeafReaderContext ctx = leafFor(doc);
        if (ctx == null) {
            return List.of();
        }
        return FieldValues.read(ctx.reader(), field, fieldType(field), doc - ctx.docBase());
    }
}
