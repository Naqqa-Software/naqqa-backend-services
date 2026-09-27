package com.naqqa.elasticsearch.node.search;

import com.naqqa.elasticsearch.cluster.routing.ShardId;
import com.naqqa.elasticsearch.index.engine.EngineSearcher;
import com.naqqa.elasticsearch.index.engine.segment.SegmentReader;
import com.naqqa.elasticsearch.index.engine.segment.StoredDocCodec;
import com.naqqa.elasticsearch.index.mapper.IdFieldMapper;
import com.naqqa.elasticsearch.index.shard.IndexShard;
import com.naqqa.elasticsearch.search.advanced.common.SegmentReaderLeafAdapter;
import com.naqqa.elasticsearch.search.execution.IndexSearcher;
import com.naqqa.elasticsearch.search.execution.LeafReader;

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class MultiShardSearcher implements Closeable {

    public record Located(ShardId shardId, SegmentReader segment, int localDoc, int globalDoc) {
    }

    private final Map<ShardId, EngineSearcher> engineSearchers;
    private final List<SegmentReader> segments = new ArrayList<>();
    private final List<ShardId> segmentShards = new ArrayList<>();
    private final List<Integer> docBases = new ArrayList<>();
    private final IndexSearcher searcher;
    private final boolean owned;

    private MultiShardSearcher(Map<ShardId, EngineSearcher> engineSearchers, boolean owned) {
        this.engineSearchers = engineSearchers;
        this.owned = owned;
        int base = 0;
        for (Map.Entry<ShardId, EngineSearcher> e : engineSearchers.entrySet()) {
            for (SegmentReader sr : e.getValue().leaves()) {
                segments.add(sr);
                segmentShards.add(e.getKey());
                docBases.add(base);
                base += sr.maxDoc();
            }
        }
        List<LeafReader> leaves = SegmentReaderLeafAdapter.wrap(segments);
        this.searcher = new IndexSearcher(leaves);
    }

    public static MultiShardSearcher open(Map<ShardId, IndexShard> shards) throws IOException {
        Map<ShardId, EngineSearcher> searchers = new LinkedHashMap<>();
        try {
            for (Map.Entry<ShardId, IndexShard> e : shards.entrySet()) {
                searchers.put(e.getKey(), e.getValue().acquireSearcher());
            }
        } catch (IOException | RuntimeException e) {
            for (EngineSearcher s : searchers.values()) {
                s.close();
            }
            throw e;
        }
        return new MultiShardSearcher(searchers, true);
    }

    public static MultiShardSearcher wrap(Map<ShardId, EngineSearcher> searchers) {
        return new MultiShardSearcher(searchers, false);
    }

    public IndexSearcher searcher() {
        return searcher;
    }

    public Map<ShardId, EngineSearcher> engineSearchers() {
        return engineSearchers;
    }

    public List<SegmentReader> segments() {
        return segments;
    }

    public List<LeafReader> leafReaders() {
        return SegmentReaderLeafAdapter.wrap(segments);
    }

    public Located locate(int globalDoc) {
        for (int i = segments.size() - 1; i >= 0; i--) {
            int base = docBases.get(i);
            if (globalDoc >= base && globalDoc < base + segments.get(i).maxDoc()) {
                return new Located(segmentShards.get(i), segments.get(i), globalDoc - base, globalDoc);
            }
        }
        return null;
    }

    public StoredDocCodec.Decoded fetch(int globalDoc) throws IOException {
        Located located = locate(globalDoc);
        return located == null ? null : located.segment().storedDocument(located.localDoc());
    }

    public ShardId shardOf(int globalDoc) {
        Located located = locate(globalDoc);
        return located == null ? null : located.shardId();
    }

    public Integer findDoc(String index, String id) throws IOException {
        for (int i = segments.size() - 1; i >= 0; i--) {
            if (index != null && !segmentShards.get(i).index().equals(index)) {
                continue;
            }
            Integer local = segments.get(i).findLiveDocForId(IdFieldMapper.NAME, id);
            if (local != null) {
                return docBases.get(i) + local;
            }
        }
        return null;
    }

    @Override
    public void close() throws IOException {
        if (owned) {
            for (EngineSearcher s : engineSearchers.values()) {
                s.close();
            }
        }
    }
}
