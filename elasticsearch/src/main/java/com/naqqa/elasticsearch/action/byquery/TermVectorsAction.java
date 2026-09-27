package com.naqqa.elasticsearch.action.byquery;

import com.naqqa.elasticsearch.action.write.IndexNameResolver;
import com.naqqa.elasticsearch.action.write.RelocationAwareRouter;
import com.naqqa.elasticsearch.action.write.SourceUtils;
import com.naqqa.elasticsearch.analysis.Analyzer;
import com.naqqa.elasticsearch.analysis.Token;
import com.naqqa.elasticsearch.analysis.TokenStream;
import com.naqqa.elasticsearch.cluster.routing.ShardId;
import com.naqqa.elasticsearch.index.engine.GetResult;
import com.naqqa.elasticsearch.index.replication.ReplicationGroup;
import com.naqqa.elasticsearch.index.shard.IndexShard;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public final class TermVectorsAction {

    private final RelocationAwareRouter router;

    public TermVectorsAction(RelocationAwareRouter router) {
        this.router = router;
    }

    public Map<String, Object> get(String index, String id, List<String> fields) throws IOException {
        long startNanos = System.nanoTime();
        IndexNameResolver.Resolution resolution = router.resolveIndex(index);
        String resolvedIndex = resolution.index();
        ShardId shardId = router.resolveShardId(resolvedIndex, id, null);
        ReplicationGroup group = router.groupFor(shardId);
        IndexShard shard = group.primary().indexShard();
        GetResult getResult = shard.get(id);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("_index", resolvedIndex);
        response.put("_id", id);
        if (!getResult.exists()) {
            response.put("found", false);
            response.put("took", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos));
            return response;
        }
        response.put("_version", getResult.version());
        response.put("found", true);

        Map<String, Object> source = SourceUtils.decode(getResult.source());
        List<String> targetFields = (fields == null || fields.isEmpty())
            ? new ArrayList<>(source == null ? Map.<String, Object>of().keySet() : source.keySet())
            : fields;

        Analyzer analyzer = shard.mapperService().indexAnalyzers().defaultAnalyzer();
        Map<String, Object> termVectors = new LinkedHashMap<>();
        for (String field : targetFields) {
            Object value = source == null ? null : source.get(field);
            if (value == null) {
                continue;
            }
            String text = value instanceof String s ? s : String.valueOf(value);
            termVectors.put(field, analyzeField(analyzer, field, text));
        }
        response.put("term_vectors", termVectors);
        response.put("took", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos));
        return response;
    }

    static Map<String, Object> analyzeField(Analyzer analyzer, String field, String text) {
        Map<String, List<Map<String, Object>>> tokensByTerm = new LinkedHashMap<>();
        TokenStream tokenStream = analyzer.tokenStream(field, text);
        try {
            tokenStream.reset();
            int position = -1;
            while (tokenStream.incrementToken()) {
                Token token = tokenStream.token();
                position += token.positionIncrement();
                Map<String, Object> occurrence = new LinkedHashMap<>();
                occurrence.put("position", position);
                occurrence.put("start_offset", token.startOffset());
                occurrence.put("end_offset", token.endOffset());
                tokensByTerm.computeIfAbsent(token.term(), key -> new ArrayList<>()).add(occurrence);
            }
            tokenStream.end();
        } finally {
            tokenStream.close();
        }

        Map<String, Object> terms = new LinkedHashMap<>();
        for (Map.Entry<String, List<Map<String, Object>>> entry : tokensByTerm.entrySet()) {
            Map<String, Object> termEntry = new LinkedHashMap<>();
            termEntry.put("term_freq", entry.getValue().size());
            termEntry.put("tokens", entry.getValue());
            terms.put(entry.getKey(), termEntry);
        }
        Map<String, Object> fieldResult = new LinkedHashMap<>();
        fieldResult.put("terms", terms);
        return fieldResult;
    }
}
