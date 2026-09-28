package com.naqqa.elasticsearch.bench.amazon;

import com.naqqa.elasticsearch.bench.QueryOp;

import java.util.ArrayList;
import java.util.List;

public final class AmazonQueries {

    private AmazonQueries() {
    }

    public static List<QueryOp> queryOps(String index, AmazonGroundTruth groundTruth, String topCategory) {
        List<QueryOp> ops = new ArrayList<>();
        String path = "/" + index + "/_search";
        String categoryJson = jsonString(topCategory == null ? "" : topCategory);
        long categoryCount = topCategory == null ? -1 : groundTruth.categoryCount(topCategory);

        ops.add(new QueryOp("search_box_wireless_bluetooth_headphones", "POST", path,
            "{\"size\":10,\"query\":{\"multi_match\":{\"query\":\"wireless bluetooth headphones\",\"fields\":[\"title\"],"
                + "\"fuzziness\":\"AUTO\"}}}"));

        ops.add(new QueryOp("search_box_stainless_steel_water_bottle", "POST", path,
            "{\"size\":10,\"query\":{\"multi_match\":{\"query\":\"stainless steel water bottle\",\"fields\":[\"title\"],"
                + "\"fuzziness\":\"AUTO\"}}}"));

        ops.add(new QueryOp("search_box_typo_blutooth_hedphones", "POST", path,
            "{\"size\":10,\"query\":{\"multi_match\":{\"query\":\"blutooth hedphones\",\"fields\":[\"title\"],"
                + "\"fuzziness\":\"AUTO\"}}}"));

        ops.add(new QueryOp("search_box_with_filters", "POST", path,
            "{\"size\":10,\"query\":{\"bool\":{\"must\":[{\"multi_match\":{\"query\":\"wireless bluetooth\","
                + "\"fields\":[\"title\"],\"fuzziness\":\"AUTO\"}}],\"filter\":["
                + "{\"range\":{\"price\":{\"gte\":" + AmazonGroundTruth.PRICE_RANGE_MIN + ",\"lte\":"
                + AmazonGroundTruth.PRICE_RANGE_MAX + "}}},"
                + "{\"range\":{\"stars\":{\"gte\":" + AmazonGroundTruth.STARS_THRESHOLD + "}}},"
                + "{\"term\":{\"isBestSeller\":true}}]}}}"));

        ops.add(new QueryOp("filter_category", "POST", path,
            "{\"size\":0,\"query\":{\"term\":{\"category_id\":" + categoryJson + "}}}", categoryCount));

        ops.add(new QueryOp("filter_price_range", "POST", path,
            "{\"size\":0,\"query\":{\"range\":{\"price\":{\"gte\":" + AmazonGroundTruth.PRICE_RANGE_MIN + ",\"lte\":"
                + AmazonGroundTruth.PRICE_RANGE_MAX + "}}}}", groundTruth.priceRangeCount()));

        ops.add(new QueryOp("filter_stars_gte4", "POST", path,
            "{\"size\":0,\"query\":{\"range\":{\"stars\":{\"gte\":" + AmazonGroundTruth.STARS_THRESHOLD + "}}}}",
            groundTruth.starsAtLeast4Count()));

        ops.add(new QueryOp("combined_bool_filter", "POST", path,
            "{\"size\":0,\"query\":{\"bool\":{\"filter\":[{\"term\":{\"isBestSeller\":true}},"
                + "{\"range\":{\"stars\":{\"gte\":" + AmazonGroundTruth.STARS_THRESHOLD + "}}},"
                + "{\"range\":{\"price\":{\"gte\":" + AmazonGroundTruth.PRICE_RANGE_MIN + ",\"lte\":"
                + AmazonGroundTruth.PRICE_RANGE_MAX + "}}}]}}}",
            groundTruth.combinedFilterCount(), QueryOp.Type.COLD_WARM));

        ops.add(new QueryOp("facets_sidebar", "POST", path,
            "{\"size\":0,\"query\":{\"multi_match\":{\"query\":\"wireless bluetooth\",\"fields\":[\"title\"]}},"
                + "\"aggs\":{\"by_category\":{\"terms\":{\"field\":\"category_id\",\"size\":20}},"
                + "\"price_ranges\":{\"range\":{\"field\":\"price\",\"ranges\":["
                + "{\"to\":15},{\"from\":15,\"to\":30},{\"from\":30,\"to\":60},{\"from\":60,\"to\":120},{\"from\":120}]}},"
                + "\"avg_stars\":{\"avg\":{\"field\":\"stars\"}},"
                + "\"max_bought\":{\"max\":{\"field\":\"boughtInLastMonth\"}}}}"));

        ops.add(new QueryOp("sort_price_asc_with_filter", "POST", path,
            "{\"size\":20,\"track_total_hits\":true,\"query\":{\"term\":{\"category_id\":" + categoryJson + "}},"
                + "\"sort\":[{\"price\":\"asc\"}]}", categoryCount));

        ops.add(new QueryOp("autocomplete_completion", "POST", path,
            "{\"size\":0,\"suggest\":{\"title-complete\":{\"prefix\":\"wireless bl\","
                + "\"completion\":{\"field\":\"title_suggest\",\"size\":5}}}}"));

        ops.add(new QueryOp("highlight_search_box", "POST", path,
            "{\"size\":5,\"query\":{\"multi_match\":{\"query\":\"wireless bluetooth headphones\",\"fields\":[\"title\"],"
                + "\"fuzziness\":\"AUTO\"}},\"highlight\":{\"fields\":{\"title\":{}}}}"));

        ops.add(new QueryOp("agg_whole_index_categories", "POST", path,
            "{\"size\":0,\"query\":{\"match_all\":{}},\"aggs\":{\"top_categories\":{\"terms\":{\"field\":\"category_id\","
                + "\"size\":20}}}}"));

        return ops;
    }

    static String jsonString(String value) {
        StringBuilder sb = new StringBuilder(value.length() + 2);
        sb.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '"' || c == '\\') {
                sb.append('\\');
            }
            sb.append(c);
        }
        sb.append('"');
        return sb.toString();
    }
}
