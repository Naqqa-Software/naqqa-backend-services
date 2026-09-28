package com.naqqa.elasticsearch.bench.amazon;

public record AmazonProduct(String asin, String title, String imgUrl, String productUrl, Float stars, int reviews,
                             double price, double listPrice, String categoryId, boolean bestSeller,
                             int boughtInLastMonth) {
}
