package com.naqqa.elasticsearch.action.search;

import com.naqqa.elasticsearch.cluster.routing.ShardId;
import com.naqqa.elasticsearch.common.io.stream.StreamInput;
import com.naqqa.elasticsearch.common.io.stream.StreamOutput;
import com.naqqa.elasticsearch.index.engine.EngineSearcher;
import com.naqqa.elasticsearch.index.engine.segment.StoredDocCodec;
import com.naqqa.elasticsearch.index.shard.IndexShard;
import com.naqqa.elasticsearch.search.aggs.InternalAggregationStreams;
import com.naqqa.elasticsearch.search.aggs.InternalAggregations;
import com.naqqa.elasticsearch.search.aggs.support.MultiBucketConsumer;
import com.naqqa.elasticsearch.search.execution.IndexSearcher;
import com.naqqa.elasticsearch.search.execution.ScoreDoc;
import com.naqqa.elasticsearch.search.execution.Sort;
import com.naqqa.elasticsearch.search.execution.TopDocs;
import com.naqqa.elasticsearch.search.execution.TopScoreDocCollector;
import com.naqqa.elasticsearch.search.execution.TotalHits;
import com.naqqa.elasticsearch.search.query.Query;
import com.naqqa.elasticsearch.search.query.Term;
import com.naqqa.elasticsearch.search.similarity.CollectionStatistics;
import com.naqqa.elasticsearch.search.similarity.TermStatistics;
import com.naqqa.elasticsearch.transport.TransportChannel;
import com.naqqa.elasticsearch.transport.TransportRequest;
import com.naqqa.elasticsearch.transport.TransportResponse;
import com.naqqa.elasticsearch.transport.TransportService;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

public final class ShardSearchService {

    public static final String ACTION_DFS = "indices:data/read/search[phase/dfs]";
    public static final String ACTION_CAN_MATCH = "indices:data/read/search[phase/can_match]";
    public static final String ACTION_QUERY = "indices:data/read/search[phase/query]";
    public static final String ACTION_FETCH = "indices:data/read/search[phase/fetch]";

    private final Function<ShardId, IndexShard> shardLookup;
    private final Map<Long, EngineSearchContext> openContexts = new ConcurrentHashMap<>();
    private final AtomicLong contextIdGenerator = new AtomicLong();

    public ShardSearchService(TransportService transportService, Function<ShardId, IndexShard> shardLookup) {
        this.shardLookup = shardLookup;
        transportService.registerRequestHandler(ACTION_DFS, DfsRequest::new, this::handleDfs);
        transportService.registerRequestHandler(ACTION_CAN_MATCH, CanMatchRequest::new, this::handleCanMatch);
        transportService.registerRequestHandler(ACTION_QUERY, ShardQueryRequest::new, this::handleQuery);
        transportService.registerRequestHandler(ACTION_FETCH, ShardFetchRequest::new, this::handleFetch);
    }

    private IndexShard shard(ShardId shardId) throws IOException {
        IndexShard shard = shardLookup.apply(shardId);
        if (shard == null) {
            throw new IOException("no such shard [" + shardId + "] on this node");
        }
        return shard;
    }

    private void handleDfs(DfsRequest request, TransportChannel channel) throws Exception {
        IndexShard shard = shard(request.shardId());
        try (EngineSearcher engineSearcher = shard.acquireSearcher()) {
            EngineSearchContext ctx = EngineSearchContext.open(engineSearcher, null);
            IndexSearcher searcher = ctx.indexSearcher();
            Map<String, CollectionStatistics> collStats = new LinkedHashMap<>();
            for (String field : request.fields()) {
                collStats.put(field, searcher.collectionStatistics(field));
            }
            Map<Term, TermStatistics> termStats = new LinkedHashMap<>();
            for (Term term : request.terms()) {
                termStats.put(term, searcher.termStatistics(term));
            }
            channel.sendResponse(new DfsResponse(request.shardId(), collStats, termStats));
        }
    }

    private void handleCanMatch(CanMatchRequest request, TransportChannel channel) throws Exception {
        IndexShard shard = shard(request.shardId());
        try (EngineSearcher engineSearcher = shard.acquireSearcher()) {
            EngineSearchContext ctx = EngineSearchContext.open(engineSearcher, null);
            boolean canMatch = CanMatchFilter.longRange()
                .mightMatch(ctx.indexSearcher(), request.field(), request.lower(), request.upper());
            channel.sendResponse(new CanMatchResponse(request.shardId(), canMatch));
        }
    }

    private void handleQuery(ShardQueryRequest request, TransportChannel channel) throws Exception {
        long startNanos = System.nanoTime();
        IndexShard shard = shard(request.shardId());
        EngineSearcher engineSearcher = shard.acquireSearcher();
        EngineSearchContext ctx = EngineSearchContext.open(engineSearcher, null);
        boolean release = true;
        try {
            IndexSearcher searcher = ctx.indexSearcher();
            if (request.dfsCollectionStats() != null) {
                searcher.useDfsStatistics(request.dfsCollectionStats(), request.dfsTermStats());
            }
            int topN = request.from() + request.size();
            TotalHits totalHits;
            List<ShardQueryResponse.Hit> hits = new ArrayList<>();
            Map<String, Object> aggsClause = request.aggs();
            InternalAggregations aggregationsResult = null;
            if (request.size() == 0) {
                if (aggsClause != null) {
                    MultiBucketConsumer bucketConsumer = new MultiBucketConsumer(request.maxBuckets());
                    AggsPhase.Result result = AggsPhase.execute(searcher, request.query(), null, aggsClause, bucketConsumer);
                    aggregationsResult = result.aggregations;
                    totalHits = new TotalHits(result.matchedDocCount, TotalHits.Relation.EQUAL_TO);
                } else {
                    totalHits = new TotalHits(searcher.count(request.query()), TotalHits.Relation.EQUAL_TO);
                }
            } else if (request.sort() != null) {
                ShardFieldCollector collector = new ShardFieldCollector(request.sort(), topN);
                if (aggsClause != null) {
                    MultiBucketConsumer bucketConsumer = new MultiBucketConsumer(request.maxBuckets());
                    AggsPhase.Result result = AggsPhase.execute(searcher, request.query(), collector, aggsClause, bucketConsumer);
                    aggregationsResult = result.aggregations;
                } else {
                    searcher.search(request.query(), collector);
                }
                totalHits = collector.totalHits();
                for (ShardFieldCollector.Hit hit : collector.results()) {
                    hits.add(new ShardQueryResponse.Hit(hit.doc(), hit.score(), hit.values()));
                }
            } else {
                TopScoreDocCollector collector = TopScoreDocCollector.create(topN);
                if (aggsClause != null) {
                    MultiBucketConsumer bucketConsumer = new MultiBucketConsumer(request.maxBuckets());
                    AggsPhase.Result result = AggsPhase.execute(searcher, request.query(), collector, aggsClause, bucketConsumer);
                    aggregationsResult = result.aggregations;
                } else {
                    searcher.search(request.query(), collector);
                }
                TopDocs topDocs = collector.topDocs();
                totalHits = topDocs.totalHits();
                for (ScoreDoc sd : topDocs.scoreDocs()) {
                    hits.add(new ShardQueryResponse.Hit(sd.doc, sd.score, null));
                }
            }
            long contextId = contextIdGenerator.incrementAndGet();
            openContexts.put(contextId, ctx);
            release = false;
            long tookNanos = System.nanoTime() - startNanos;
            channel.sendResponse(new ShardQueryResponse(request.shardId(), contextId, totalHits, hits, tookNanos, aggregationsResult));
        } finally {
            if (release) {
                ctx.close();
            }
        }
    }

    private void handleFetch(ShardFetchRequest request, TransportChannel channel) throws Exception {
        EngineSearchContext ctx = openContexts.remove(request.contextId());
        try {
            List<ShardFetchResponse.FetchedDoc> docs = new ArrayList<>();
            if (ctx != null) {
                for (int docId : request.docIds()) {
                    StoredDocCodec.Decoded decoded = ctx.fetch(docId);
                    if (decoded == null) {
                        docs.add(new ShardFetchResponse.FetchedDoc(null, null));
                    } else {
                        docs.add(new ShardFetchResponse.FetchedDoc(decoded.id(), decoded.source()));
                    }
                }
            } else {
                for (int i = 0; i < request.docIds().length; i++) {
                    docs.add(new ShardFetchResponse.FetchedDoc(null, null));
                }
            }
            channel.sendResponse(new ShardFetchResponse(request.shardId(), docs));
        } finally {
            if (ctx != null) {
                ctx.close();
            }
        }
    }

    static void writeShardId(StreamOutput out, ShardId shardId) throws IOException {
        out.writeString(shardId.index());
        out.writeVInt(shardId.id());
    }

    static ShardId readShardId(StreamInput in) throws IOException {
        return new ShardId(in.readString(), in.readVInt());
    }

    public static final class DfsRequest implements TransportRequest {
        private final ShardId shardId;
        private final List<String> fields;
        private final List<Term> terms;

        public DfsRequest(ShardId shardId, List<String> fields, List<Term> terms) {
            this.shardId = shardId;
            this.fields = fields;
            this.terms = terms;
        }

        DfsRequest(StreamInput in) throws IOException {
            this.shardId = readShardId(in);
            this.fields = in.readStringList();
            int count = in.readVInt();
            this.terms = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                String field = in.readString();
                byte[] bytes = in.readByteArray();
                terms.add(new Term(field, bytes));
            }
        }

        public ShardId shardId() {
            return shardId;
        }

        public List<String> fields() {
            return fields;
        }

        public List<Term> terms() {
            return terms;
        }

        @Override
        public void writeTo(StreamOutput out) throws IOException {
            writeShardId(out, shardId);
            out.writeStringCollection(fields);
            out.writeVInt(terms.size());
            for (Term term : terms) {
                out.writeString(term.field());
                out.writeByteArray(term.bytes());
            }
        }
    }

    public static final class DfsResponse implements TransportResponse {
        private final ShardId shardId;
        private final Map<String, CollectionStatistics> collectionStats;
        private final Map<Term, TermStatistics> termStats;

        public DfsResponse(ShardId shardId, Map<String, CollectionStatistics> collectionStats, Map<Term, TermStatistics> termStats) {
            this.shardId = shardId;
            this.collectionStats = collectionStats;
            this.termStats = termStats;
        }

        DfsResponse(StreamInput in) throws IOException {
            this.shardId = readShardId(in);
            int collCount = in.readVInt();
            this.collectionStats = new LinkedHashMap<>();
            for (int i = 0; i < collCount; i++) {
                String field = in.readString();
                long maxDoc = in.readVLong();
                long docCount = in.readVLong();
                long sumDocFreq = in.readVLong();
                long sumTotalTermFreq = in.readVLong();
                collectionStats.put(field, new CollectionStatistics(field, maxDoc, docCount, sumDocFreq, sumTotalTermFreq));
            }
            int termCount = in.readVInt();
            this.termStats = new LinkedHashMap<>();
            for (int i = 0; i < termCount; i++) {
                String field = in.readString();
                byte[] bytes = in.readByteArray();
                long docFreq = in.readVLong();
                long totalTermFreq = in.readVLong();
                termStats.put(new Term(field, bytes), new TermStatistics(bytes, docFreq, totalTermFreq));
            }
        }

        public ShardId shardId() {
            return shardId;
        }

        public Map<String, CollectionStatistics> collectionStats() {
            return collectionStats;
        }

        public Map<Term, TermStatistics> termStats() {
            return termStats;
        }

        @Override
        public void writeTo(StreamOutput out) throws IOException {
            writeShardId(out, shardId);
            out.writeVInt(collectionStats.size());
            for (Map.Entry<String, CollectionStatistics> e : collectionStats.entrySet()) {
                CollectionStatistics cs = e.getValue();
                out.writeString(e.getKey());
                out.writeVLong(cs.maxDoc());
                out.writeVLong(cs.docCount());
                out.writeVLong(cs.sumDocFreq());
                out.writeVLong(cs.sumTotalTermFreq());
            }
            out.writeVInt(termStats.size());
            for (Map.Entry<Term, TermStatistics> e : termStats.entrySet()) {
                TermStatistics ts = e.getValue();
                out.writeString(e.getKey().field());
                out.writeByteArray(e.getKey().bytes());
                out.writeVLong(ts.docFreq());
                out.writeVLong(ts.totalTermFreq());
            }
        }
    }

    public static final class CanMatchRequest implements TransportRequest {
        private final ShardId shardId;
        private final String field;
        private final long lower;
        private final long upper;

        public CanMatchRequest(ShardId shardId, String field, long lower, long upper) {
            this.shardId = shardId;
            this.field = field;
            this.lower = lower;
            this.upper = upper;
        }

        CanMatchRequest(StreamInput in) throws IOException {
            this.shardId = readShardId(in);
            this.field = in.readString();
            this.lower = in.readZLong();
            this.upper = in.readZLong();
        }

        public ShardId shardId() {
            return shardId;
        }

        public String field() {
            return field;
        }

        public long lower() {
            return lower;
        }

        public long upper() {
            return upper;
        }

        @Override
        public void writeTo(StreamOutput out) throws IOException {
            writeShardId(out, shardId);
            out.writeString(field);
            out.writeZLong(lower);
            out.writeZLong(upper);
        }
    }

    public static final class CanMatchResponse implements TransportResponse {
        private final ShardId shardId;
        private final boolean canMatch;

        public CanMatchResponse(ShardId shardId, boolean canMatch) {
            this.shardId = shardId;
            this.canMatch = canMatch;
        }

        CanMatchResponse(StreamInput in) throws IOException {
            this.shardId = readShardId(in);
            this.canMatch = in.readBoolean();
        }

        public ShardId shardId() {
            return shardId;
        }

        public boolean canMatch() {
            return canMatch;
        }

        @Override
        public void writeTo(StreamOutput out) throws IOException {
            writeShardId(out, shardId);
            out.writeBoolean(canMatch);
        }
    }

    public static final class ShardQueryRequest implements TransportRequest {
        private final ShardId shardId;
        private final Query query;
        private final Sort sort;
        private final int from;
        private final int size;
        private final Map<String, CollectionStatistics> dfsCollectionStats;
        private final Map<Term, TermStatistics> dfsTermStats;
        private final Map<String, Object> aggs;
        private final int maxBuckets;

        public ShardQueryRequest(ShardId shardId, Query query, Sort sort, int from, int size,
                                  Map<String, CollectionStatistics> dfsCollectionStats, Map<Term, TermStatistics> dfsTermStats) {
            this(shardId, query, sort, from, size, dfsCollectionStats, dfsTermStats, null, MultiBucketConsumer.DEFAULT_MAX_BUCKETS);
        }

        public ShardQueryRequest(ShardId shardId, Query query, Sort sort, int from, int size,
                                  Map<String, CollectionStatistics> dfsCollectionStats, Map<Term, TermStatistics> dfsTermStats,
                                  Map<String, Object> aggs, int maxBuckets) {
            this.shardId = shardId;
            this.query = query;
            this.sort = sort;
            this.from = from;
            this.size = size;
            this.dfsCollectionStats = dfsCollectionStats;
            this.dfsTermStats = dfsTermStats;
            this.aggs = aggs;
            this.maxBuckets = maxBuckets;
        }

        ShardQueryRequest(StreamInput in) throws IOException {
            this.shardId = readShardId(in);
            this.query = QueryCodec.readQuery(in);
            this.sort = QueryCodec.readSort(in);
            this.from = in.readVInt();
            this.size = in.readVInt();
            if (in.readBoolean()) {
                int collCount = in.readVInt();
                Map<String, CollectionStatistics> coll = new LinkedHashMap<>();
                for (int i = 0; i < collCount; i++) {
                    String field = in.readString();
                    long maxDoc = in.readVLong();
                    long docCount = in.readVLong();
                    long sumDocFreq = in.readVLong();
                    long sumTotalTermFreq = in.readVLong();
                    coll.put(field, new CollectionStatistics(field, maxDoc, docCount, sumDocFreq, sumTotalTermFreq));
                }
                int termCount = in.readVInt();
                Map<Term, TermStatistics> terms = new LinkedHashMap<>();
                for (int i = 0; i < termCount; i++) {
                    String field = in.readString();
                    byte[] bytes = in.readByteArray();
                    long docFreq = in.readVLong();
                    long totalTermFreq = in.readVLong();
                    terms.put(new Term(field, bytes), new TermStatistics(bytes, docFreq, totalTermFreq));
                }
                this.dfsCollectionStats = coll;
                this.dfsTermStats = terms;
            } else {
                this.dfsCollectionStats = null;
                this.dfsTermStats = null;
            }
            if (in.readBoolean()) {
                @SuppressWarnings("unchecked")
                Map<String, Object> aggsValue = (Map<String, Object>) in.readGenericValue();
                this.aggs = aggsValue;
            } else {
                this.aggs = null;
            }
            this.maxBuckets = in.readVInt();
        }

        public ShardId shardId() {
            return shardId;
        }

        public Query query() {
            return query;
        }

        public Sort sort() {
            return sort;
        }

        public int from() {
            return from;
        }

        public int size() {
            return size;
        }

        public Map<String, CollectionStatistics> dfsCollectionStats() {
            return dfsCollectionStats;
        }

        public Map<Term, TermStatistics> dfsTermStats() {
            return dfsTermStats;
        }

        public Map<String, Object> aggs() {
            return aggs;
        }

        public int maxBuckets() {
            return maxBuckets;
        }

        @Override
        public void writeTo(StreamOutput out) throws IOException {
            writeShardId(out, shardId);
            QueryCodec.writeQuery(out, query);
            QueryCodec.writeSort(out, sort);
            out.writeVInt(from);
            out.writeVInt(size);
            out.writeBoolean(dfsCollectionStats != null);
            if (dfsCollectionStats != null) {
                out.writeVInt(dfsCollectionStats.size());
                for (Map.Entry<String, CollectionStatistics> e : dfsCollectionStats.entrySet()) {
                    CollectionStatistics cs = e.getValue();
                    out.writeString(e.getKey());
                    out.writeVLong(cs.maxDoc());
                    out.writeVLong(cs.docCount());
                    out.writeVLong(cs.sumDocFreq());
                    out.writeVLong(cs.sumTotalTermFreq());
                }
                out.writeVInt(dfsTermStats.size());
                for (Map.Entry<Term, TermStatistics> e : dfsTermStats.entrySet()) {
                    TermStatistics ts = e.getValue();
                    out.writeString(e.getKey().field());
                    out.writeByteArray(e.getKey().bytes());
                    out.writeVLong(ts.docFreq());
                    out.writeVLong(ts.totalTermFreq());
                }
            }
            out.writeBoolean(aggs != null);
            if (aggs != null) {
                out.writeGenericValue(aggs);
            }
            out.writeVInt(maxBuckets);
        }
    }

    public static final class ShardQueryResponse implements TransportResponse {

        public record Hit(int doc, float score, Object[] values) {
        }

        private final ShardId shardId;
        private final long contextId;
        private final TotalHits totalHits;
        private final List<Hit> hits;
        private final long tookNanos;
        private final InternalAggregations aggregations;

        public ShardQueryResponse(ShardId shardId, long contextId, TotalHits totalHits, List<Hit> hits, long tookNanos) {
            this(shardId, contextId, totalHits, hits, tookNanos, null);
        }

        public ShardQueryResponse(ShardId shardId, long contextId, TotalHits totalHits, List<Hit> hits, long tookNanos,
                                   InternalAggregations aggregations) {
            this.shardId = shardId;
            this.contextId = contextId;
            this.totalHits = totalHits;
            this.hits = hits;
            this.tookNanos = tookNanos;
            this.aggregations = aggregations;
        }

        ShardQueryResponse(StreamInput in) throws IOException {
            this.shardId = readShardId(in);
            this.contextId = in.readVLong();
            long value = in.readVLong();
            TotalHits.Relation relation = TotalHits.Relation.valueOf(in.readString());
            this.totalHits = new TotalHits(value, relation);
            int count = in.readVInt();
            this.hits = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                int doc = in.readVInt();
                float score = in.readFloat();
                Object[] values = null;
                if (in.readBoolean()) {
                    int n = in.readVInt();
                    values = new Object[n];
                    for (int j = 0; j < n; j++) {
                        values[j] = in.readGenericValue();
                    }
                }
                hits.add(new Hit(doc, score, values));
            }
            this.tookNanos = in.readVLong();
            this.aggregations = in.readBoolean() ? InternalAggregationStreams.readAggregations(in) : null;
        }

        public ShardId shardId() {
            return shardId;
        }

        public long contextId() {
            return contextId;
        }

        public TotalHits totalHits() {
            return totalHits;
        }

        public List<Hit> hits() {
            return hits;
        }

        public long tookNanos() {
            return tookNanos;
        }

        public InternalAggregations aggregations() {
            return aggregations;
        }

        @Override
        public void writeTo(StreamOutput out) throws IOException {
            writeShardId(out, shardId);
            out.writeVLong(contextId);
            out.writeVLong(totalHits.value());
            out.writeString(totalHits.relation().name());
            out.writeVInt(hits.size());
            for (Hit hit : hits) {
                out.writeVInt(hit.doc());
                out.writeFloat(hit.score());
                out.writeBoolean(hit.values() != null);
                if (hit.values() != null) {
                    out.writeVInt(hit.values().length);
                    for (Object v : hit.values()) {
                        out.writeGenericValue(v);
                    }
                }
            }
            out.writeVLong(tookNanos);
            out.writeBoolean(aggregations != null);
            if (aggregations != null) {
                InternalAggregationStreams.writeAggregations(out, aggregations);
            }
        }
    }

    public static final class ShardFetchRequest implements TransportRequest {
        private final ShardId shardId;
        private final long contextId;
        private final int[] docIds;

        public ShardFetchRequest(ShardId shardId, long contextId, int[] docIds) {
            this.shardId = shardId;
            this.contextId = contextId;
            this.docIds = docIds;
        }

        ShardFetchRequest(StreamInput in) throws IOException {
            this.shardId = readShardId(in);
            this.contextId = in.readVLong();
            int count = in.readVInt();
            this.docIds = new int[count];
            for (int i = 0; i < count; i++) {
                docIds[i] = in.readVInt();
            }
        }

        public ShardId shardId() {
            return shardId;
        }

        public long contextId() {
            return contextId;
        }

        public int[] docIds() {
            return docIds;
        }

        @Override
        public void writeTo(StreamOutput out) throws IOException {
            writeShardId(out, shardId);
            out.writeVLong(contextId);
            out.writeVInt(docIds.length);
            for (int docId : docIds) {
                out.writeVInt(docId);
            }
        }
    }

    public static final class ShardFetchResponse implements TransportResponse {

        public record FetchedDoc(String id, byte[] source) {
        }

        private final ShardId shardId;
        private final List<FetchedDoc> docs;

        public ShardFetchResponse(ShardId shardId, List<FetchedDoc> docs) {
            this.shardId = shardId;
            this.docs = docs;
        }

        ShardFetchResponse(StreamInput in) throws IOException {
            this.shardId = readShardId(in);
            int count = in.readVInt();
            this.docs = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                if (in.readBoolean()) {
                    docs.add(new FetchedDoc(in.readString(), in.readByteArray()));
                } else {
                    docs.add(new FetchedDoc(null, null));
                }
            }
        }

        public ShardId shardId() {
            return shardId;
        }

        public List<FetchedDoc> docs() {
            return docs;
        }

        @Override
        public void writeTo(StreamOutput out) throws IOException {
            writeShardId(out, shardId);
            out.writeVInt(docs.size());
            for (FetchedDoc doc : docs) {
                boolean present = doc.id() != null;
                out.writeBoolean(present);
                if (present) {
                    out.writeString(doc.id());
                    out.writeByteArray(doc.source());
                }
            }
        }
    }
}
