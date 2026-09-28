package com.naqqa.elasticsearch.bench.amazon;

import java.util.List;
import java.util.Locale;

public final class AmazonProductParser {

    public static final int EXPECTED_COLUMNS = 11;

    private AmazonProductParser() {
    }

    public static AmazonProduct parse(List<String> fields) {
        if (fields == null || fields.size() != EXPECTED_COLUMNS) {
            return null;
        }
        String asin = trimOrNull(fields.get(0));
        if (asin == null) {
            return null;
        }
        String title = fields.get(1) == null ? "" : fields.get(1);
        String imgUrl = fields.get(2) == null ? "" : fields.get(2);
        String productUrl = fields.get(3) == null ? "" : fields.get(3);
        Float stars = parseFloatOrNull(fields.get(4));
        int reviews = parseIntOrDefault(fields.get(5), 0);
        Double price = parseDoubleOrNull(fields.get(6));
        if (price == null || price.isNaN() || price < 0) {
            return null;
        }
        double listPrice = parseDoubleOrDefault(fields.get(7), 0.0);
        String categoryId = trimOrNull(fields.get(8));
        if (categoryId == null) {
            categoryId = "";
        }
        boolean bestSeller = parseBoolean(fields.get(9));
        int boughtInLastMonth = parseIntOrDefault(fields.get(10), 0);
        return new AmazonProduct(asin, title, imgUrl, productUrl, stars, reviews, price, listPrice, categoryId,
            bestSeller, boughtInLastMonth);
    }

    private static String trimOrNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static Float parseFloatOrNull(String s) {
        if (s == null || s.trim().isEmpty()) {
            return null;
        }
        try {
            return Float.parseFloat(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Double parseDoubleOrNull(String s) {
        if (s == null || s.trim().isEmpty()) {
            return null;
        }
        try {
            return Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static double parseDoubleOrDefault(String s, double def) {
        Double v = parseDoubleOrNull(s);
        return v == null ? def : v;
    }

    private static int parseIntOrDefault(String s, int def) {
        if (s == null || s.trim().isEmpty()) {
            return def;
        }
        try {
            return (int) Math.round(Double.parseDouble(s.trim()));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static boolean parseBoolean(String s) {
        if (s == null) {
            return false;
        }
        return s.trim().equalsIgnoreCase("true");
    }
}
