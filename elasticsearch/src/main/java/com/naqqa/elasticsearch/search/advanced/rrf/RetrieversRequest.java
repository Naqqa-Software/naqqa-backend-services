package com.naqqa.elasticsearch.search.advanced.rrf;

import com.naqqa.elasticsearch.index.query.QueryBuilder;
import com.naqqa.elasticsearch.index.query.QueryParser;
import com.naqqa.elasticsearch.search.advanced.common.QueryBuilderToQuery;
import com.naqqa.elasticsearch.search.execution.IndexSearcher;
import com.naqqa.elasticsearch.search.execution.ScoreDoc;
import com.naqqa.elasticsearch.search.execution.TopDocs;
import com.naqqa.elasticsearch.search.query.Query;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public abstract class RetrieversRequest {

    public abstract List<Integer> resolve(IndexSearcher searcher, int windowSize) throws IOException;

    public static final class Standard extends RetrieversRequest {
        private final Query query;

        public Standard(Query query) {
            this.query = query;
        }

        public Query query() {
            return query;
        }

        @Override
        public List<Integer> resolve(IndexSearcher searcher, int windowSize) throws IOException {
            TopDocs topDocs = searcher.search(query, windowSize);
            List<Integer> ids = new ArrayList<>();
            for (ScoreDoc sd : topDocs.scoreDocs()) {
                ids.add(sd.doc);
            }
            return ids;
        }
    }

    public static final class Knn extends RetrieversRequest {
        private final String field;
        private final float[] queryVector;
        private final int k;

        public Knn(String field, float[] queryVector, int k) {
            this.field = field;
            this.queryVector = queryVector;
            this.k = k;
        }

        public String field() {
            return field;
        }

        public float[] queryVector() {
            return queryVector;
        }

        public int k() {
            return k;
        }

        @Override
        public List<Integer> resolve(IndexSearcher searcher, int windowSize) {
            return List.of();
        }
    }

    public static final class Rrf extends RetrieversRequest {
        private final List<RetrieversRequest> children;
        private final int rankConstant;

        public Rrf(List<RetrieversRequest> children, int rankConstant) {
            this.children = children;
            this.rankConstant = rankConstant;
        }

        public List<RetrieversRequest> children() {
            return children;
        }

        public int rankConstant() {
            return rankConstant;
        }

        @Override
        public List<Integer> resolve(IndexSearcher searcher, int windowSize) throws IOException {
            List<List<Integer>> rankedLists = new ArrayList<>();
            for (RetrieversRequest child : children) {
                rankedLists.add(child.resolve(searcher, windowSize));
            }
            List<RrfRetriever.RrfHit> hits = RrfRetriever.fuse(rankedLists, rankConstant, windowSize);
            List<Integer> ids = new ArrayList<>();
            for (RrfRetriever.RrfHit h : hits) {
                ids.add(h.docId());
            }
            return ids;
        }
    }

    @SuppressWarnings("unchecked")
    public static RetrieversRequest fromMap(Map<String, Object> map) {
        if (map.containsKey("standard")) {
            Map<String, Object> std = (Map<String, Object>) map.get("standard");
            Map<String, Object> queryMap = (Map<String, Object>) std.get("query");
            QueryBuilder qb = QueryParser.parseQuery(queryMap);
            return new Standard(QueryBuilderToQuery.convert(qb));
        }
        if (map.containsKey("knn")) {
            Map<String, Object> knn = (Map<String, Object>) map.get("knn");
            String field = String.valueOf(knn.get("field"));
            List<Number> vec = (List<Number>) knn.get("query_vector");
            float[] arr = new float[vec == null ? 0 : vec.size()];
            for (int i = 0; i < arr.length; i++) {
                arr[i] = vec.get(i).floatValue();
            }
            int k = knn.get("k") instanceof Number n ? n.intValue() : 10;
            return new Knn(field, arr, k);
        }
        if (map.containsKey("rrf")) {
            Map<String, Object> rrf = (Map<String, Object>) map.get("rrf");
            List<Map<String, Object>> retrievers = (List<Map<String, Object>>) rrf.get("retrievers");
            List<RetrieversRequest> children = new ArrayList<>();
            for (Map<String, Object> r : retrievers) {
                children.add(fromMap(r));
            }
            int rankConstant = rrf.get("rank_constant") instanceof Number n ? n.intValue() : 20;
            return new Rrf(children, rankConstant);
        }
        throw new IllegalArgumentException("unknown retriever definition: " + map.keySet());
    }
}
