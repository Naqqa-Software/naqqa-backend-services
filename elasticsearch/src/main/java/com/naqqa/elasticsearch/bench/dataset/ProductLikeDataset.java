package com.naqqa.elasticsearch.bench.dataset;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

public final class ProductLikeDataset {

    public record ProductDoc(String id, String sku, String name, String description, String brand, String category,
                              List<String> categoryPath, double price, int discountPercent, boolean inStock, int stockQty,
                              double rating, long reviewCount, List<String> tags, String color, String size,
                              long createdAtMillis, long updatedAtMillis) {
    }

    public record CategoryNode(String top, String mid, String leaf, String fullPath, List<String> path) {
    }

    public static final int VOCAB_SIZE = 4_000;
    public static final double VOCAB_ZIPF_EXPONENT = 1.1;
    public static final double BRAND_ZIPF_EXPONENT = 1.15;

    private static final String[] COMMON_WORDS = {
        "the", "of", "and", "a", "to", "in", "is", "for", "with", "as", "on", "at", "by", "this", "that", "from",
        "or", "an", "it", "its", "your", "our", "you", "will", "can", "into", "over", "under", "great", "perfect",
        "ideal", "everyday", "home", "office", "family", "friends", "gift", "season", "collection", "style",
        "comfort", "design", "quality", "value", "care", "life", "world", "brand", "customer", "shipping"
    };

    private static final String[] PRODUCT_WORDS = {
        "durable", "premium", "warranty", "assembly", "material", "performance", "versatile", "innovative",
        "reliable", "sturdy", "sleek", "ergonomic", "efficient", "affordable", "stylish", "trendy", "functional",
        "practical", "portable", "compact", "lightweight", "adjustable", "waterproof", "breathable", "washable",
        "rechargeable", "wireless", "handmade", "imported", "organic", "eco-friendly", "energy-efficient",
        "multi-purpose", "heavy-duty", "foldable", "modular", "seamless", "long-lasting", "easy-to-clean",
        "shock-resistant", "anti-slip", "noise-cancelling", "fast-charging", "high-resolution", "wide-angle"
    };

    private static final String[] BRAND_WORD_A = {
        "Nova", "Peak", "Aster", "Bright", "Silver", "Golden", "North", "Crest", "Vivid", "Prime", "Urban",
        "Alpine", "Coral", "Amber", "Bold", "Clear", "Swift", "Grand", "True", "Pure", "Solid", "Blue", "Red",
        "Star", "Wild"
    };

    private static final String[] BRAND_WORD_B = {
        "Works", "Traders", "Robotics", "Goods", "Labs", "Supply", "Craft", "Studio", "Gear", "House", "Forge",
        "Collective", "Group", "Co", "Industries", "Designs", "Outfitters", "Systems", "Ventures", "Mercantile"
    };

    public static final String[] BRANDS = buildBrands();

    private static final String[] TOPS = {
        "electronics", "home", "clothing", "sports", "toys", "beauty", "automotive", "garden", "books", "grocery"
    };

    private static final String[][] MIDS = {
        {"tv", "audio", "computers", "phones"},
        {"kitchen", "furniture", "decor", "bedding"},
        {"mens", "womens", "kids", "shoes"},
        {"fitness", "outdoor", "teamsports", "cycling"},
        {"educational", "action-figures", "boardgames", "outdoor-toys"},
        {"skincare", "makeup", "haircare", "fragrance"},
        {"parts", "accessories", "tires", "electronics"},
        {"tools", "plants", "furniture", "decor"},
        {"fiction", "nonfiction", "childrens", "comics"},
        {"snacks", "beverages", "produce", "pantry"}
    };

    private static final String[][][] LEAVES = {
        {
            {"oled", "qled", "led", "smart-tv", "projector"},
            {"soundbar", "headphones", "earbuds", "speaker", "turntable"},
            {"laptop", "desktop", "monitor", "keyboard", "mouse"},
            {"smartphone", "case", "charger", "screen-protector", "powerbank"}
        },
        {
            {"blender", "toaster", "cookware", "dinnerware", "knife-set"},
            {"sofa", "dining-table", "bookshelf", "office-chair", "bed-frame"},
            {"wall-art", "rug", "curtains", "mirror", "candle"},
            {"comforter", "pillow", "sheet-set", "mattress-topper", "blanket"}
        },
        {
            {"shirt", "jeans", "jacket", "suit", "activewear"},
            {"dress", "blouse", "skirt", "jeans", "activewear"},
            {"onesie", "t-shirt", "shorts", "pajamas", "jacket"},
            {"sneakers", "boots", "sandals", "loafers", "running-shoes"}
        },
        {
            {"treadmill", "dumbbell", "yoga-mat", "resistance-band", "exercise-bike"},
            {"tent", "sleeping-bag", "backpack", "hiking-boots", "camp-stove"},
            {"soccer-ball", "basketball", "baseball-glove", "hockey-stick", "jersey"},
            {"road-bike", "mountain-bike", "helmet", "bike-lock", "bike-light"}
        },
        {
            {"puzzle", "flashcards", "building-blocks", "science-kit", "globe"},
            {"superhero", "robot", "dinosaur", "soldier", "vehicle"},
            {"strategy", "family", "card-game", "trivia", "party-game"},
            {"trampoline", "water-gun", "sandbox", "swing-set", "kite"}
        },
        {
            {"moisturizer", "cleanser", "serum", "sunscreen", "toner"},
            {"lipstick", "foundation", "mascara", "eyeshadow", "blush"},
            {"shampoo", "conditioner", "hair-dryer", "straightener", "hair-oil"},
            {"perfume", "cologne", "body-spray", "essential-oil", "candle"}
        },
        {
            {"brake-pad", "air-filter", "spark-plug", "battery", "alternator"},
            {"floor-mat", "seat-cover", "phone-mount", "dash-cam", "roof-rack"},
            {"all-season", "winter", "performance", "off-road", "run-flat"},
            {"gps", "backup-camera", "radar-detector", "car-stereo", "dash-lighting"}
        },
        {
            {"shovel", "rake", "pruner", "wheelbarrow", "hose"},
            {"seedling", "shrub", "perennial", "succulent", "herb"},
            {"patio-set", "hammock", "bench", "umbrella", "planter-stand"},
            {"gnome", "wind-chime", "solar-light", "fountain", "birdhouse"}
        },
        {
            {"mystery", "romance", "scifi", "fantasy", "thriller"},
            {"biography", "history", "selfhelp", "science", "cookbook"},
            {"picture-book", "early-reader", "middle-grade", "activity-book", "bedtime-story"},
            {"superhero", "manga", "graphic-novel", "indie", "anthology"}
        },
        {
            {"chips", "cookies", "granola-bar", "popcorn", "crackers"},
            {"coffee", "tea", "juice", "soda", "sparkling-water"},
            {"fruit-box", "vegetable-box", "organic-mix", "salad-kit", "herbs"},
            {"pasta", "rice", "canned-goods", "sauce", "spices"}
        }
    };

    private static final Set<String> ACRONYM_TOKENS = Set.of("tv", "gps", "4k", "hd", "uv", "led", "oled", "qled");

    private static final Set<String> SIZE_SPEC_LEAVES = Set.of("oled", "qled", "led", "smart-tv", "projector", "laptop", "monitor");

    private static final String[] ADJECTIVES = {
        "Ultra Slim", "Premium", "Deluxe", "Compact", "Portable", "Professional", "Wireless", "Eco-Friendly",
        "Heavy-Duty", "Lightweight", "Classic", "Modern", "Vintage", "Rustic", "Elegant", "Sporty", "Waterproof",
        "Durable", "Adjustable", "Foldable", "Smart", "4K", "Essential", "All-in-One"
    };

    private static final String[] TAGS = {
        "bestseller", "new-arrival", "eco-friendly", "on-sale", "limited-edition", "top-rated", "clearance",
        "exclusive", "handmade", "imported", "organic", "waterproof", "wireless", "rechargeable", "gift-idea",
        "trending", "budget-friendly", "kids-safe", "pet-friendly", "premium-choice", "staff-pick", "seasonal",
        "backorder", "free-shipping", "warranty-included", "recyclable-packaging", "energy-efficient",
        "limited-stock", "customer-favorite", "new-model"
    };

    private static final String[] COLORS = {
        "black", "white", "silver", "gray", "red", "blue", "green", "yellow", "pink", "purple", "orange", "brown",
        "gold", "beige", "navy", "teal", "maroon", "ivory", "charcoal", "multicolor"
    };

    private static final String[] CLOTHING_SIZES = {"XS", "S", "M", "L", "XL", "XXL", "One Size"};

    public static final List<CategoryNode> CATEGORY_NODES = buildCategoryNodes();

    public static final String TARGET_BRAND = BRANDS[7];
    public static final CategoryNode TARGET_CATEGORY_NODE = CATEGORY_NODES.get(3);
    public static final String TARGET_CATEGORY = TARGET_CATEGORY_NODE.fullPath();
    public static final double PRICE_RANGE_MIN = 50.0;
    public static final double PRICE_RANGE_MAX = 800.0;
    public static final double RATING_THRESHOLD = 4.0;
    public static final String TAG_A = "bestseller";
    public static final String TAG_B = "on-sale";

    private static final long BASE_CREATED_MILLIS = Instant.parse("2021-01-01T00:00:00Z").toEpochMilli();
    private static final long RANGE_END_MILLIS = Instant.parse("2024-09-01T00:00:00Z").toEpochMilli();
    private static final long CREATED_RANGE_MILLIS = RANGE_END_MILLIS - BASE_CREATED_MILLIS;

    private static final double[] TOP_BASE_PRICE = {300, 120, 40, 80, 25, 20, 150, 60, 15, 8};

    private final long seed;
    private final String[] vocabulary;
    private final AliasSampler vocabSampler;
    private final AliasSampler brandSampler;

    public ProductLikeDataset(long seed) {
        this.seed = seed;
        this.vocabulary = buildVocabulary();
        this.vocabSampler = new AliasSampler(AliasSampler.zipfWeights(VOCAB_SIZE, VOCAB_ZIPF_EXPONENT));
        this.brandSampler = new AliasSampler(AliasSampler.zipfWeights(BRANDS.length, BRAND_ZIPF_EXPONENT));
    }

    private static String[] buildBrands() {
        String[] brands = new String[BRAND_WORD_A.length * BRAND_WORD_B.length];
        int i = 0;
        for (String a : BRAND_WORD_A) {
            for (String b : BRAND_WORD_B) {
                brands[i++] = a + " " + b;
            }
        }
        return brands;
    }

    private static List<CategoryNode> buildCategoryNodes() {
        List<CategoryNode> nodes = new ArrayList<>();
        for (int t = 0; t < TOPS.length; t++) {
            String top = TOPS[t];
            String[] mids = MIDS[t];
            String[][] leavesForTop = LEAVES[t];
            for (int m = 0; m < mids.length; m++) {
                String mid = mids[m];
                String midPath = top + " > " + mid;
                for (String leaf : leavesForTop[m]) {
                    String fullPath = midPath + " > " + leaf;
                    nodes.add(new CategoryNode(top, mid, leaf, fullPath, List.of(top, midPath, fullPath)));
                }
            }
        }
        return List.copyOf(nodes);
    }

    private static String[] buildVocabulary() {
        String[] vocab = new String[VOCAB_SIZE];
        int idx = 0;
        for (String w : COMMON_WORDS) {
            vocab[idx++] = w;
        }
        for (String w : PRODUCT_WORDS) {
            vocab[idx++] = w;
        }
        for (int i = idx; i < VOCAB_SIZE; i++) {
            vocab[i] = "prodterm" + i;
        }
        return vocab;
    }

    public static long mix(long seed, long index) {
        return WikiLikeDataset.mix(seed ^ 0x7A3C55F19E2B01L, index);
    }

    private static String titleCaseWithAcronyms(String slug) {
        StringBuilder sb = new StringBuilder();
        for (String token : slug.split("-")) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            if (ACRONYM_TOKENS.contains(token.toLowerCase(Locale.ROOT))) {
                sb.append(token.toUpperCase(Locale.ROOT));
            } else {
                sb.append(Character.toUpperCase(token.charAt(0))).append(token.substring(1));
            }
        }
        return sb.toString();
    }

    public ProductDoc product(long index) {
        Random rnd = new Random(mix(seed, index));

        CategoryNode cat = CATEGORY_NODES.get(rnd.nextInt(CATEGORY_NODES.size()));
        int topIdx = indexOfTop(cat.top());
        String brand = BRANDS[brandSampler.sample(rnd)];

        int adjIdx1 = rnd.nextInt(ADJECTIVES.length);
        int adjIdx2 = rnd.nextInt(ADJECTIVES.length);
        while (adjIdx2 == adjIdx1) {
            adjIdx2 = rnd.nextInt(ADJECTIVES.length);
        }
        String productType = titleCaseWithAcronyms(cat.leaf());
        String sizeSpec = null;
        if (SIZE_SPEC_LEAVES.contains(cat.leaf())) {
            int inches = 13 + rnd.nextInt(70);
            sizeSpec = inches + " Inch";
        }
        StringBuilder nameBuilder = new StringBuilder();
        nameBuilder.append(brand).append(' ').append(ADJECTIVES[adjIdx1]);
        if (sizeSpec != null) {
            nameBuilder.append(' ').append(sizeSpec);
        }
        nameBuilder.append(' ').append(ADJECTIVES[adjIdx2]).append(' ').append(productType);
        String name = nameBuilder.toString();

        int wordCount = 30 + (int) Math.round(170 * Math.pow(rnd.nextDouble(), 2.0));
        StringBuilder desc = new StringBuilder(wordCount * 7);
        for (int i = 0; i < wordCount; i++) {
            if (i > 0) {
                desc.append(' ');
            }
            desc.append(vocabulary[vocabSampler.sample(rnd)]);
        }

        double basePrice = TOP_BASE_PRICE[topIdx];
        double gaussian = nextGaussian(rnd) * 0.6;
        double price = round2(Math.max(1.0, basePrice * Math.exp(gaussian)));

        int discountPercent = rnd.nextDouble() < 0.6 ? 0 : 5 * (1 + rnd.nextInt(14));
        boolean inStock = rnd.nextDouble() < 0.88;
        int stockQty = inStock ? 1 + rnd.nextInt(500) : 0;

        double rating = 5.0 - 4.0 * Math.pow(rnd.nextDouble(), 3);
        rating = Math.round(Math.max(1.0, Math.min(5.0, rating)) * 10.0) / 10.0;

        long reviewCount = (long) Math.round(Math.pow(10, rnd.nextDouble() * 4)) - 1;
        reviewCount = Math.max(0, reviewCount);

        int tagCount = rnd.nextInt(7);
        Set<String> tagSet = new LinkedHashSet<>();
        int guard = 0;
        while (tagSet.size() < tagCount && guard < 50) {
            tagSet.add(TAGS[rnd.nextInt(TAGS.length)]);
            guard++;
        }
        List<String> tags = List.copyOf(tagSet);

        String color = COLORS[rnd.nextInt(COLORS.length)];
        String size = "clothing".equals(cat.top()) ? CLOTHING_SIZES[rnd.nextInt(CLOTHING_SIZES.length)] : null;

        long createdAtMillis = BASE_CREATED_MILLIS + (long) (rnd.nextDouble() * CREATED_RANGE_MILLIS);
        long updatedAtMillis = createdAtMillis + (long) (rnd.nextDouble() * (RANGE_END_MILLIS - createdAtMillis));

        String sku = "SKU-" + cat.leaf().toUpperCase(Locale.ROOT).replace('-', '_') + "-" + String.format(Locale.ROOT, "%07d", index);

        return new ProductDoc("prod-" + index, sku, name, desc.toString(), brand, cat.fullPath(), cat.path(), price,
            discountPercent, inStock, stockQty, rating, reviewCount, tags, color, size, createdAtMillis, updatedAtMillis);
    }

    private static int indexOfTop(String top) {
        for (int i = 0; i < TOPS.length; i++) {
            if (TOPS[i].equals(top)) {
                return i;
            }
        }
        return 0;
    }

    private static double nextGaussian(Random rnd) {
        double u1 = Math.max(1e-12, rnd.nextDouble());
        double u2 = rnd.nextDouble();
        return Math.sqrt(-2.0 * Math.log(u1)) * Math.cos(2.0 * Math.PI * u2);
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    public Map<String, Object> toSource(ProductDoc doc) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sku", doc.sku());
        m.put("name", doc.name());
        m.put("description", doc.description());
        m.put("brand", doc.brand());
        m.put("category", doc.category());
        m.put("category_path", doc.categoryPath());
        m.put("price", doc.price());
        if (doc.discountPercent() > 0) {
            m.put("discount_percent", doc.discountPercent());
        }
        m.put("in_stock", doc.inStock());
        m.put("stock_qty", doc.stockQty());
        m.put("rating", doc.rating());
        m.put("review_count", doc.reviewCount());
        m.put("tags", doc.tags());
        m.put("color", doc.color());
        if (doc.size() != null) {
            m.put("size", doc.size());
        }
        m.put("created_at", Instant.ofEpochMilli(doc.createdAtMillis()).toString());
        m.put("updated_at", Instant.ofEpochMilli(doc.updatedAtMillis()).toString());
        List<String> suggestInputs = new ArrayList<>();
        suggestInputs.add(doc.name());
        suggestInputs.add(doc.brand() + " " + doc.name());
        Map<String, Object> suggest = new LinkedHashMap<>();
        suggest.put("input", suggestInputs);
        suggest.put("weight", Math.min(2_000_000_000L, 1 + doc.reviewCount()));
        m.put("suggest", suggest);
        return m;
    }

    public static Map<String, Object> mapping(int shards) {
        Map<String, Object> nameFields = new LinkedHashMap<>();
        Map<String, Object> nameKeyword = new LinkedHashMap<>();
        nameKeyword.put("type", "keyword");
        nameKeyword.put("ignore_above", 256);
        nameFields.put("keyword", nameKeyword);

        Map<String, Object> nameField = new LinkedHashMap<>();
        nameField.put("type", "text");
        nameField.put("analyzer", "english");
        nameField.put("fields", nameFields);

        Map<String, Object> descriptionField = new LinkedHashMap<>();
        descriptionField.put("type", "text");
        descriptionField.put("analyzer", "english");

        Map<String, Object> priceField = new LinkedHashMap<>();
        priceField.put("type", "scaled_float");
        priceField.put("scaling_factor", 100);

        Map<String, Object> suggestField = new LinkedHashMap<>();
        suggestField.put("type", "completion");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("sku", Map.of("type", "keyword"));
        properties.put("name", nameField);
        properties.put("description", descriptionField);
        properties.put("brand", Map.of("type", "keyword"));
        properties.put("category", Map.of("type", "keyword"));
        properties.put("category_path", Map.of("type", "keyword"));
        properties.put("price", priceField);
        properties.put("discount_percent", Map.of("type", "integer"));
        properties.put("in_stock", Map.of("type", "boolean"));
        properties.put("stock_qty", Map.of("type", "integer"));
        properties.put("rating", Map.of("type", "float"));
        properties.put("review_count", Map.of("type", "long"));
        properties.put("tags", Map.of("type", "keyword"));
        properties.put("color", Map.of("type", "keyword"));
        properties.put("size", Map.of("type", "keyword"));
        properties.put("created_at", Map.of("type", "date"));
        properties.put("updated_at", Map.of("type", "date"));
        properties.put("suggest", suggestField);

        Map<String, Object> mappings = Map.of("properties", properties);
        Map<String, Object> settings = Map.of("number_of_shards", shards);
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("settings", settings);
        root.put("mappings", mappings);
        return root;
    }
}
