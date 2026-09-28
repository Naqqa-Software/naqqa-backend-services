package com.naqqa.elasticsearch.bench.amazon;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class AmazonGroundTruth {

    public static final double PRICE_RANGE_MIN = 20.0;
    public static final double PRICE_RANGE_MAX = 100.0;
    public static final double STARS_THRESHOLD = 4.0;
    public static final int BOUGHT_THRESHOLD = 1000;
    public static final int SAMPLE_SIZE = 5;

    private long totalRows = 0;
    private long malformedRows = 0;
    private long parsedOk = 0;
    private long bestSellerCount = 0;
    private long stars45Count = 0;
    private long starsAtLeast4Count = 0;
    private long boughtGte1000Count = 0;
    private long listPriceExistsCount = 0;
    private long priceRangeCount = 0;
    private long combinedFilterCount = 0;
    private final Map<String, Long> categoryCounts = new HashMap<>();
    private final List<AmazonProduct> samples = new ArrayList<>(SAMPLE_SIZE);

    public void incrementTotalRows() {
        totalRows++;
    }

    public void incrementMalformed() {
        malformedRows++;
    }

    public void accumulate(AmazonProduct p) {
        parsedOk++;
        if (p.bestSeller()) {
            bestSellerCount++;
        }
        boolean starsGte4 = p.stars() != null && p.stars() >= STARS_THRESHOLD;
        if (p.stars() != null && p.stars() >= 4.5f) {
            stars45Count++;
        }
        if (starsGte4) {
            starsAtLeast4Count++;
        }
        if (p.boughtInLastMonth() >= BOUGHT_THRESHOLD) {
            boughtGte1000Count++;
        }
        if (p.listPrice() > 0.0) {
            listPriceExistsCount++;
        }
        boolean priceInRange = p.price() >= PRICE_RANGE_MIN && p.price() <= PRICE_RANGE_MAX;
        if (priceInRange) {
            priceRangeCount++;
        }
        if (p.bestSeller() && starsGte4 && priceInRange) {
            combinedFilterCount++;
        }
        categoryCounts.merge(p.categoryId(), 1L, Long::sum);
        if (samples.size() < SAMPLE_SIZE) {
            samples.add(p);
        }
    }

    public long totalRows() {
        return totalRows;
    }

    public long malformedRows() {
        return malformedRows;
    }

    public long parsedOk() {
        return parsedOk;
    }

    public long bestSellerCount() {
        return bestSellerCount;
    }

    public long stars45Count() {
        return stars45Count;
    }

    public long starsAtLeast4Count() {
        return starsAtLeast4Count;
    }

    public long boughtGte1000Count() {
        return boughtGte1000Count;
    }

    public long listPriceExistsCount() {
        return listPriceExistsCount;
    }

    public long priceRangeCount() {
        return priceRangeCount;
    }

    public long combinedFilterCount() {
        return combinedFilterCount;
    }

    public List<AmazonProduct> samples() {
        return samples;
    }

    public String topCategory() {
        String best = null;
        long bestCount = -1;
        for (Map.Entry<String, Long> e : categoryCounts.entrySet()) {
            if (!e.getKey().isEmpty() && e.getValue() > bestCount) {
                bestCount = e.getValue();
                best = e.getKey();
            }
        }
        return best;
    }

    public long categoryCount(String categoryId) {
        return categoryCounts.getOrDefault(categoryId, 0L);
    }

    public int categoryCardinality() {
        return categoryCounts.size();
    }
}
