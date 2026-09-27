package com.naqqa.elasticsearch.search.advanced.percolate;

import com.naqqa.elasticsearch.index.query.QueryBuilder;
import com.naqqa.elasticsearch.search.advanced.common.QueryBuilderToQuery;
import com.naqqa.elasticsearch.search.execution.IndexSearcher;
import com.naqqa.elasticsearch.search.execution.SimpleLeafReader;
import com.naqqa.elasticsearch.search.query.Query;
import com.naqqa.elasticsearch.search.similarity.Explanation;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class PercolatorExecutor {

    private PercolatorExecutor() {
    }

    public static Map<Integer, List<PercolateMatch>> percolate(List<Map<String, String>> documents,
                                                                 Map<String, QueryBuilder> storedQueries) throws IOException {
        SimpleLeafReader reader = PercolateSegmentBuilder.build(documents);
        IndexSearcher searcher = new IndexSearcher(List.of(reader));
        Map<Integer, List<PercolateMatch>> result = new LinkedHashMap<>();
        for (int d = 0; d < documents.size(); d++) {
            result.put(d, new ArrayList<>());
        }
        for (Map.Entry<String, QueryBuilder> e : storedQueries.entrySet()) {
            Query query;
            try {
                query = QueryBuilderToQuery.convert(e.getValue());
            } catch (IllegalArgumentException unsupported) {
                continue;
            }
            for (int d = 0; d < documents.size(); d++) {
                Explanation explanation = searcher.explain(query, d);
                if (explanation.isMatch()) {
                    result.get(d).add(new PercolateMatch(e.getKey(), explanation.value()));
                }
            }
        }
        return result;
    }

    public static List<PercolateMatch> percolate(Map<String, String> document, Map<String, QueryBuilder> storedQueries) throws IOException {
        return percolate(List.of(document), storedQueries).get(0);
    }
}
