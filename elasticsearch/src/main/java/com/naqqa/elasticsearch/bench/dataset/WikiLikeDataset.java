package com.naqqa.elasticsearch.bench.dataset;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

public final class WikiLikeDataset {

    public record WikiDoc(String id, String title, String body, List<String> categories, long timestampMillis, long popularity) {
    }

    public static final int VOCAB_SIZE = 50_000;
    public static final double ZIPF_EXPONENT = 1.07;

    private static final String[] COMMON_WORDS = {
        "the", "of", "and", "a", "to", "in", "is", "was", "he", "for", "it", "with", "as", "his", "on", "be",
        "at", "by", "i", "this", "had", "not", "are", "but", "from", "or", "have", "an", "they", "which", "one",
        "you", "were", "her", "all", "she", "there", "would", "their", "we", "him", "been", "has", "when", "who",
        "will", "more", "no", "if", "out", "so", "said", "what", "up", "its", "about", "into", "than", "them",
        "can", "only", "other", "new", "some", "could", "time", "these", "two", "may", "then", "do", "first",
        "any", "my", "now", "such", "like", "our", "over", "man", "me", "even", "most", "made", "after", "also",
        "did", "many", "before", "must", "through", "back", "years", "where", "much", "your", "way", "well",
        "down", "should", "because", "each", "just", "those", "people", "how", "too", "little", "state", "good",
        "very", "make", "world", "still", "see", "own", "men", "work", "long", "get", "here", "between", "both",
        "life", "being", "under", "need", "house", "during", "war", "without", "again", "since", "government",
        "day", "might", "part", "place", "form", "system", "number", "large", "history", "science", "area",
        "power", "case", "point", "government", "company", "group", "problem", "fact", "water", "century",
        "system", "night", "art", "language", "energy", "family", "student", "law", "team", "market", "city",
        "country", "player", "book", "music", "film", "war", "study", "series", "network", "value", "policy"
    };

    private static final String[] CATEGORIES = {
        "history", "science", "geography", "technology", "biography", "sports", "music", "film", "politics",
        "literature", "mathematics", "astronomy", "biology", "chemistry", "physics", "art", "architecture",
        "economics", "military", "religion", "philosophy", "medicine", "law", "education", "language",
        "transportation", "food", "nature", "society", "culture"
    };

    private final long seed;
    private final String[] vocabulary;
    private final AliasSampler sampler;
    private final long baseTimestampMillis;
    private final long timestampRangeMillis;

    public WikiLikeDataset(long seed) {
        this.seed = seed;
        this.vocabulary = buildVocabulary();
        this.sampler = new AliasSampler(AliasSampler.zipfWeights(VOCAB_SIZE, ZIPF_EXPONENT));
        this.baseTimestampMillis = Instant.parse("2019-01-01T00:00:00Z").toEpochMilli();
        this.timestampRangeMillis = Instant.parse("2024-01-01T00:00:00Z").toEpochMilli() - baseTimestampMillis;
    }

    private static String[] buildVocabulary() {
        String[] vocab = new String[VOCAB_SIZE];
        System.arraycopy(COMMON_WORDS, 0, vocab, 0, COMMON_WORDS.length);
        for (int i = COMMON_WORDS.length; i < VOCAB_SIZE; i++) {
            vocab[i] = "syn" + i;
        }
        return vocab;
    }

    public static long mix(long seed, long index) {
        long h = seed ^ (index * 0x9E3779B97F4A7C15L);
        h ^= (h >>> 33);
        h *= 0xff51afd7ed558ccdL;
        h ^= (h >>> 33);
        h *= 0xc4ceb9fe1a85ec53L;
        h ^= (h >>> 33);
        return h;
    }

    public WikiDoc doc(long index) {
        Random rnd = new Random(mix(seed, index));
        int wordCount = 50 + (int) Math.round(1950 * Math.pow(rnd.nextDouble(), 2.5));
        StringBuilder body = new StringBuilder(wordCount * 6);
        for (int i = 0; i < wordCount; i++) {
            if (i > 0) {
                body.append(' ');
            }
            body.append(vocabulary[sampler.sample(rnd)]);
        }
        int titleWords = 3 + rnd.nextInt(6);
        StringBuilder title = new StringBuilder();
        for (int i = 0; i < titleWords; i++) {
            if (i > 0) {
                title.append(' ');
            }
            String w = vocabulary[sampler.sample(rnd)];
            title.append(i == 0 ? capitalize(w) : w);
        }
        int catCount = 1 + rnd.nextInt(3);
        Set<String> cats = new LinkedHashSet<>();
        while (cats.size() < catCount) {
            cats.add(CATEGORIES[rnd.nextInt(CATEGORIES.length)]);
        }
        long timestampMillis = baseTimestampMillis + (long) (rnd.nextDouble() * timestampRangeMillis);
        long popularity = (long) Math.pow(10, rnd.nextDouble() * 6);
        return new WikiDoc("wiki-" + index, title.toString(), body.toString(), List.copyOf(cats), timestampMillis, popularity);
    }

    private static String capitalize(String w) {
        if (w.isEmpty()) {
            return w;
        }
        return Character.toUpperCase(w.charAt(0)) + w.substring(1);
    }

    public Map<String, Object> toSource(WikiDoc doc) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("title", doc.title());
        m.put("body", doc.body());
        m.put("categories", doc.categories());
        m.put("timestamp", Instant.ofEpochMilli(doc.timestampMillis()).toString());
        m.put("popularity", doc.popularity());
        return m;
    }

    public String[] categories() {
        return CATEGORIES.clone();
    }

    public static Map<String, Object> mapping(int shards) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("title", Map.of("type", "text"));
        properties.put("body", Map.of("type", "text"));
        properties.put("categories", Map.of("type", "keyword"));
        properties.put("timestamp", Map.of("type", "date"));
        properties.put("popularity", Map.of("type", "long"));
        Map<String, Object> mappings = Map.of("properties", properties);
        Map<String, Object> settings = Map.of("number_of_shards", shards);
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("settings", settings);
        root.put("mappings", mappings);
        return root;
    }
}
