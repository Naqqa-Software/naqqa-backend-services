package com.naqqa.elasticsearch.bench;

import com.naqqa.elasticsearch.bench.dataset.ProductLikeDataset;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class ProductTrack implements Track {

    public static final String TARGET_BRAND = ProductLikeDataset.TARGET_BRAND;
    public static final String TARGET_CATEGORY = ProductLikeDataset.TARGET_CATEGORY;
    public static final double PRICE_RANGE_MIN = ProductLikeDataset.PRICE_RANGE_MIN;
    public static final double PRICE_RANGE_MAX = ProductLikeDataset.PRICE_RANGE_MAX;
    public static final double RATING_THRESHOLD = ProductLikeDataset.RATING_THRESHOLD;
    public static final String TAG_A = ProductLikeDataset.TAG_A;
    public static final String TAG_B = ProductLikeDataset.TAG_B;
    public static final int PAGE_SIZE = 20;
    public static final int FROM_OFFSET = 1000;
    public static final int SEARCH_AFTER_PAGES = 50;

    private final ProductLikeDataset dataset;
    private final String indexName;

    public ProductTrack(long seed, String indexName) {
        this.dataset = new ProductLikeDataset(seed);
        this.indexName = indexName;
    }

    public ProductLikeDataset dataset() {
        return dataset;
    }

    @Override
    public String name() {
        return "products";
    }

    @Override
    public String indexName() {
        return indexName;
    }

    @Override
    public Map<String, Object> mapping(int shards) {
        return ProductLikeDataset.mapping(shards);
    }

    @Override
    public BulkIndexer.DocSource docSource() {
        return index -> dataset.toSource(dataset.product(index));
    }

    @Override
    public Map<String, Long> groundTruth(long docCount) {
        long brandCount = 0;
        long categoryCount = 0;
        long priceRangeCount = 0;
        long ratingCount = 0;
        long inStockCount = 0;
        long tagsCount = 0;
        long existsDiscountCount = 0;
        long comboCount = 0;
        for (long i = 0; i < docCount; i++) {
            ProductLikeDataset.ProductDoc doc = dataset.product(i);
            boolean brandMatch = TARGET_BRAND.equals(doc.brand());
            boolean categoryMatch = TARGET_CATEGORY.equals(doc.category());
            boolean priceMatch = doc.price() >= PRICE_RANGE_MIN && doc.price() <= PRICE_RANGE_MAX;
            boolean ratingMatch = doc.rating() >= RATING_THRESHOLD;
            boolean stockMatch = doc.inStock();
            if (brandMatch) {
                brandCount++;
            }
            if (categoryMatch) {
                categoryCount++;
            }
            if (priceMatch) {
                priceRangeCount++;
            }
            if (ratingMatch) {
                ratingCount++;
            }
            if (stockMatch) {
                inStockCount++;
            }
            if (doc.tags().contains(TAG_A) || doc.tags().contains(TAG_B)) {
                tagsCount++;
            }
            if (doc.discountPercent() > 0) {
                existsDiscountCount++;
            }
            if (brandMatch && categoryMatch && priceMatch && ratingMatch && stockMatch) {
                comboCount++;
            }
        }
        Map<String, Long> stats = new LinkedHashMap<>();
        stats.put("doc_count", docCount);
        stats.put("brand_count", brandCount);
        stats.put("category_count", categoryCount);
        stats.put("price_range_count", priceRangeCount);
        stats.put("rating_count", ratingCount);
        stats.put("in_stock_count", inStockCount);
        stats.put("tags_count", tagsCount);
        stats.put("exists_discount_count", existsDiscountCount);
        stats.put("combo_count", comboCount);
        return stats;
    }

    @Override
    public List<QueryOp> queryOps(String index, long docCount, Map<String, Long> groundTruth) {
        List<QueryOp> ops = new ArrayList<>();
        String path = "/" + index + "/_search";
        String brandJson = jsonString(TARGET_BRAND);
        String categoryJson = jsonString(TARGET_CATEGORY);

        ops.add(new QueryOp("search_box_multi_match", "POST", path,
            "{\"size\":10,\"query\":{\"multi_match\":{\"query\":\"premium wireless speaker\",\"fields\":[\"name^3\",\"description\"],"
                + "\"fuzziness\":\"AUTO\"}}}"));

        ops.add(new QueryOp("search_box_multi_match_typo", "POST", path,
            "{\"size\":10,\"query\":{\"multi_match\":{\"query\":\"premiu wireles spaeker\",\"fields\":[\"name^3\",\"description\"],"
                + "\"fuzziness\":\"AUTO\"}}}"));

        ops.add(new QueryOp("search_box_with_filters", "POST", path,
            "{\"size\":10,\"query\":{\"bool\":{\"must\":[{\"multi_match\":{\"query\":\"premium wireless\",\"fields\":[\"name^3\","
                + "\"description\"],\"fuzziness\":\"AUTO\"}}],\"filter\":[{\"term\":{\"brand\":" + brandJson + "}},"
                + "{\"range\":{\"price\":{\"gte\":" + PRICE_RANGE_MIN + ",\"lte\":" + PRICE_RANGE_MAX + "}}},"
                + "{\"term\":{\"in_stock\":true}}]}}}"));

        ops.add(new QueryOp("term_brand", "POST", path,
            "{\"size\":0,\"query\":{\"term\":{\"brand\":" + brandJson + "}}}",
            groundTruth.get("brand_count")));

        ops.add(new QueryOp("term_category", "POST", path,
            "{\"size\":0,\"query\":{\"term\":{\"category\":" + categoryJson + "}}}",
            groundTruth.get("category_count")));

        ops.add(new QueryOp("range_price", "POST", path,
            "{\"size\":0,\"query\":{\"range\":{\"price\":{\"gte\":" + PRICE_RANGE_MIN + ",\"lte\":" + PRICE_RANGE_MAX + "}}}}",
            groundTruth.get("price_range_count")));

        ops.add(new QueryOp("range_rating", "POST", path,
            "{\"size\":0,\"query\":{\"range\":{\"rating\":{\"gte\":" + RATING_THRESHOLD + "}}}}",
            groundTruth.get("rating_count")));

        ops.add(new QueryOp("filter_in_stock", "POST", path,
            "{\"size\":0,\"query\":{\"term\":{\"in_stock\":true}}}",
            groundTruth.get("in_stock_count")));

        ops.add(new QueryOp("terms_tags", "POST", path,
            "{\"size\":0,\"query\":{\"terms\":{\"tags\":[" + jsonString(TAG_A) + "," + jsonString(TAG_B) + "]}}}",
            groundTruth.get("tags_count")));

        ops.add(new QueryOp("exists_discount", "POST", path,
            "{\"size\":0,\"query\":{\"exists\":{\"field\":\"discount_percent\"}}}",
            groundTruth.get("exists_discount_count")));

        ops.add(new QueryOp("combined_bool_filter", "POST", path,
            "{\"size\":0,\"query\":{\"bool\":{\"filter\":[{\"term\":{\"brand\":" + brandJson + "}},"
                + "{\"term\":{\"category\":" + categoryJson + "}},"
                + "{\"range\":{\"price\":{\"gte\":" + PRICE_RANGE_MIN + ",\"lte\":" + PRICE_RANGE_MAX + "}}},"
                + "{\"term\":{\"in_stock\":true}},{\"range\":{\"rating\":{\"gte\":" + RATING_THRESHOLD + "}}}]}}}",
            groundTruth.get("combo_count"), QueryOp.Type.COLD_WARM));

        ops.add(new QueryOp("facets_sidebar", "POST", path,
            "{\"size\":0,\"query\":{\"multi_match\":{\"query\":\"premium wireless\",\"fields\":[\"name^3\",\"description\"]}},"
                + "\"aggs\":{\"by_brand\":{\"terms\":{\"field\":\"brand\",\"size\":10}},"
                + "\"by_category\":{\"terms\":{\"field\":\"category\",\"size\":10}},"
                + "\"price_ranges\":{\"range\":{\"field\":\"price\",\"ranges\":["
                + "{\"to\":50},{\"from\":50,\"to\":150},{\"from\":150,\"to\":400},{\"from\":400,\"to\":800},{\"from\":800}]}},"
                + "\"avg_rating\":{\"avg\":{\"field\":\"rating\"}}}}"));

        ops.add(new QueryOp("sort_price_asc", "POST", path,
            "{\"size\":20,\"track_total_hits\":true,\"query\":{\"term\":{\"category\":" + categoryJson + "}},"
                + "\"sort\":[{\"price\":\"asc\"}]}",
            groundTruth.get("category_count")));

        ops.add(new QueryOp("sort_price_desc", "POST", path,
            "{\"size\":20,\"track_total_hits\":true,\"query\":{\"term\":{\"category\":" + categoryJson + "}},"
                + "\"sort\":[{\"price\":\"desc\"}]}",
            groundTruth.get("category_count")));

        ops.add(new QueryOp("sort_rating_desc", "POST", path,
            "{\"size\":20,\"track_total_hits\":true,\"query\":{\"term\":{\"in_stock\":true}},\"sort\":[{\"rating\":\"desc\"}]}",
            groundTruth.get("in_stock_count")));

        ops.add(new QueryOp("deep_paging_from_size", "POST", path,
            "{\"size\":" + PAGE_SIZE + ",\"from\":" + FROM_OFFSET + ",\"track_total_hits\":true,\"query\":{\"match_all\":{}}}",
            docCount));

        long searchAfterExpected = Math.min((long) SEARCH_AFTER_PAGES * PAGE_SIZE, docCount);
        ops.add(new QueryOp("deep_paging_search_after", "POST", path,
            "{\"size\":" + PAGE_SIZE + ",\"query\":{\"match_all\":{}},\"sort\":[{\"price\":\"asc\"},{\"_doc\":\"asc\"}]}",
            searchAfterExpected, QueryOp.Type.SEARCH_AFTER));

        String suggestPrefix = TARGET_BRAND.split(" ")[0].toLowerCase(Locale.ROOT);
        ops.add(new QueryOp("autocomplete_completion", "POST", path,
            "{\"size\":0,\"suggest\":{\"name-complete\":{\"prefix\":" + jsonString(suggestPrefix)
                + ",\"completion\":{\"field\":\"suggest\",\"size\":5}}}}"));

        ops.add(new QueryOp("autocomplete_match_phrase_prefix", "POST", path,
            "{\"size\":10,\"query\":{\"match_phrase_prefix\":{\"name\":\"ultra slim\"}}}"));

        ops.add(new QueryOp("highlight_search_box", "POST", path,
            "{\"size\":5,\"query\":{\"multi_match\":{\"query\":\"premium wireless\",\"fields\":[\"name^3\",\"description\"],"
                + "\"fuzziness\":\"AUTO\"}},\"highlight\":{\"fields\":{\"name\":{},\"description\":{}}}}"));

        return ops;
    }

    private static String jsonString(String value) {
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
