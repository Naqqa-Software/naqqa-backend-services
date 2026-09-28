package com.naqqa.elasticsearch.bench;

import com.naqqa.elasticsearch.bench.dataset.WikiLikeDataset;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class WikiTrack implements Track {

    public static final String TARGET_CATEGORY = "science";
    public static final long POPULARITY_THRESHOLD = 1000L;
    public static final String RECENT_DATE = "2022-01-01";

    private final WikiLikeDataset dataset;
    private final String indexName;

    public WikiTrack(long seed, String indexName) {
        this.dataset = new WikiLikeDataset(seed);
        this.indexName = indexName;
    }

    public WikiLikeDataset dataset() {
        return dataset;
    }

    @Override
    public String name() {
        return "wiki";
    }

    @Override
    public String indexName() {
        return indexName;
    }

    @Override
    public Map<String, Object> mapping(int shards) {
        return WikiLikeDataset.mapping(shards);
    }

    @Override
    public BulkIndexer.DocSource docSource() {
        return index -> dataset.toSource(dataset.doc(index));
    }

    @Override
    public Map<String, Long> groundTruth(long docCount) {
        long recentMillis = java.time.Instant.parse(RECENT_DATE + "T00:00:00Z").toEpochMilli();
        long categoryCount = 0;
        long popularityCount = 0;
        long recentCount = 0;
        for (long i = 0; i < docCount; i++) {
            WikiLikeDataset.WikiDoc doc = dataset.doc(i);
            if (doc.categories().contains(TARGET_CATEGORY)) {
                categoryCount++;
            }
            if (doc.popularity() >= POPULARITY_THRESHOLD) {
                popularityCount++;
            }
            if (doc.timestampMillis() >= recentMillis) {
                recentCount++;
            }
        }
        Map<String, Long> stats = new LinkedHashMap<>();
        stats.put("doc_count", docCount);
        stats.put("category_count", categoryCount);
        stats.put("popularity_count", popularityCount);
        stats.put("recent_count", recentCount);
        return stats;
    }

    @Override
    public List<QueryOp> queryOps(String index, long docCount, Map<String, Long> groundTruth) {
        List<QueryOp> ops = new ArrayList<>();
        String path = "/" + index + "/_search";
        ops.add(new QueryOp("match_single_word", "POST", path,
            "{\"size\":10,\"query\":{\"match\":{\"body\":\"history\"}}}"));
        ops.add(new QueryOp("bool_multi_word", "POST", path,
            "{\"size\":10,\"query\":{\"bool\":{\"must\":[{\"match\":{\"body\":\"history\"}},{\"match\":{\"body\":\"science\"}}]}}}"));
        ops.add(new QueryOp("match_phrase", "POST", path,
            "{\"size\":10,\"query\":{\"match_phrase\":{\"body\":\"world war\"}}}"));
        ops.add(new QueryOp("term_category", "POST", path,
            "{\"size\":0,\"query\":{\"term\":{\"categories\":\"" + TARGET_CATEGORY + "\"}}}",
            groundTruth.get("category_count")));
        ops.add(new QueryOp("range_numeric_popularity", "POST", path,
            "{\"size\":0,\"query\":{\"range\":{\"popularity\":{\"gte\":" + POPULARITY_THRESHOLD + "}}}}",
            groundTruth.get("popularity_count")));
        ops.add(new QueryOp("range_date", "POST", path,
            "{\"size\":0,\"query\":{\"range\":{\"timestamp\":{\"gte\":\"" + RECENT_DATE + "\"}}}}",
            groundTruth.get("recent_count")));
        ops.add(new QueryOp("terms_agg_categories", "POST", path,
            "{\"size\":0,\"track_total_hits\":true,\"aggs\":{\"cats\":{\"terms\":{\"field\":\"categories\",\"size\":30}}}}", docCount));
        ops.add(new QueryOp("date_histogram_agg", "POST", path,
            "{\"size\":0,\"track_total_hits\":true,"
                + "\"aggs\":{\"by_month\":{\"date_histogram\":{\"field\":\"timestamp\",\"calendar_interval\":\"month\"}}}}",
            docCount));
        ops.add(new QueryOp("avg_percentiles_agg", "POST", path,
            "{\"size\":0,\"track_total_hits\":true,\"aggs\":{\"avg_pop\":{\"avg\":{\"field\":\"popularity\"}},"
                + "\"pct_pop\":{\"percentiles\":{\"field\":\"popularity\"}}}}", docCount));
        ops.add(new QueryOp("sort_by_field", "POST", path,
            "{\"size\":20,\"track_total_hits\":true,\"sort\":[{\"popularity\":\"desc\"}]}", docCount));
        ops.add(new QueryOp("scroll_all_docs", "POST", path + "?scroll=1m",
            "{\"size\":500,\"query\":{\"match_all\":{}}}", docCount, QueryOp.Type.SCROLL));
        ops.add(new QueryOp("highlight_search", "POST", path,
            "{\"size\":5,\"query\":{\"match\":{\"body\":\"history\"}},\"highlight\":{\"fields\":{\"body\":{}}}}"));
        ops.add(new QueryOp("bool_filter_category_range", "POST", path,
            "{\"size\":0,\"track_total_hits\":true,\"query\":{\"bool\":{\"filter\":["
                + "{\"term\":{\"categories\":\"" + TARGET_CATEGORY + "\"}},"
                + "{\"range\":{\"popularity\":{\"gte\":" + POPULARITY_THRESHOLD + "}}}]}}}"));
        return ops;
    }
}
