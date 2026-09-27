package com.naqqa.elasticsearch.index.query;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class MoreLikeThisQueryBuilder extends AbstractQueryBuilder {

    public static final String NAME = "more_like_this";

    public static final class Item {

        private String index;
        private String id;
        private Map<String, Object> doc;
        private List<String> fields;

        public static Item fromMap(Map<String, Object> map) {
            Item item = new Item();
            item.index = QueryParseUtils.asString(map.get("_index"));
            item.id = QueryParseUtils.asString(map.get("_id"));
            Object doc = map.get("doc");
            if (doc != null) {
                item.doc = QueryParseUtils.asMap(doc, "doc");
            }
            Object fields = map.get("fields");
            if (fields != null) {
                item.fields = QueryParseUtils.asStringList(fields);
            }
            return item;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            if (index != null) {
                m.put("_index", index);
            }
            if (id != null) {
                m.put("_id", id);
            }
            if (doc != null) {
                m.put("doc", doc);
            }
            if (fields != null) {
                m.put("fields", fields);
            }
            return m;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Item other)) {
                return false;
            }
            return Objects.equals(index, other.index) && Objects.equals(id, other.id) && Objects.equals(doc, other.doc) && Objects.equals(fields, other.fields);
        }

        @Override
        public int hashCode() {
            return Objects.hash(index, id, doc, fields);
        }
    }

    private final List<String> fields = new ArrayList<>();
    private final List<Object> like = new ArrayList<>();
    private final List<Object> unlike = new ArrayList<>();
    private int minTermFreq = 2;
    private int maxQueryTerms = 25;
    private int minDocFreq = 5;
    private int maxDocFreq = Integer.MAX_VALUE;
    private int minWordLength = 0;
    private int maxWordLength = 0;
    private final List<String> stopWords = new ArrayList<>();
    private String analyzer;
    private MinimumShouldMatch minimumShouldMatch;
    private Float boostTerms;
    private boolean include = false;
    private boolean failOnUnsupportedField = true;

    private static final Set<String> KNOWN_FIELDS = Set.of("fields", "like", "unlike", "min_term_freq", "max_query_terms",
        "min_doc_freq", "max_doc_freq", "min_word_length", "max_word_length", "stop_words", "analyzer",
        "minimum_should_match", "boost_terms", "include", "fail_on_unsupported_field", "boost", "_name");

    public static MoreLikeThisQueryBuilder fromMap(Map<String, Object> value) {
        MoreLikeThisQueryBuilder builder = new MoreLikeThisQueryBuilder();
        Object fields = value.remove("fields");
        if (fields != null) {
            builder.fields.addAll(QueryParseUtils.asStringList(fields));
        }
        Object like = value.remove("like");
        if (like == null) {
            throw QueryParseUtils.error("[{}] requires [like]", NAME);
        }
        builder.like.addAll(parseLikeList(like));
        Object unlike = value.remove("unlike");
        if (unlike != null) {
            builder.unlike.addAll(parseLikeList(unlike));
        }
        for (Map.Entry<String, Object> e : value.entrySet()) {
            String key = e.getKey();
            Object v = e.getValue();
            switch (key) {
                case "min_term_freq" -> builder.minTermFreq = QueryParseUtils.asInt(v);
                case "max_query_terms" -> builder.maxQueryTerms = QueryParseUtils.asInt(v);
                case "min_doc_freq" -> builder.minDocFreq = QueryParseUtils.asInt(v);
                case "max_doc_freq" -> builder.maxDocFreq = QueryParseUtils.asInt(v);
                case "min_word_length" -> builder.minWordLength = QueryParseUtils.asInt(v);
                case "max_word_length" -> builder.maxWordLength = QueryParseUtils.asInt(v);
                case "stop_words" -> builder.stopWords.addAll(QueryParseUtils.asStringList(v));
                case "analyzer" -> builder.analyzer = QueryParseUtils.asString(v);
                case "minimum_should_match" -> builder.minimumShouldMatch = MinimumShouldMatch.of(v);
                case "boost_terms" -> builder.boostTerms = QueryParseUtils.asFloat(v);
                case "include" -> builder.include = QueryParseUtils.asBoolean(v);
                case "fail_on_unsupported_field" -> builder.failOnUnsupportedField = QueryParseUtils.asBoolean(v);
                case "boost", "_name" -> builder.readCommon(key, v);
                default -> throw QueryParseUtils.unknownField(NAME, key, KNOWN_FIELDS);
            }
        }
        return builder;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> parseLikeList(Object value) {
        List<Object> out = new ArrayList<>();
        for (Object o : QueryParseUtils.asList(value, NAME)) {
            if (o instanceof Map<?, ?>) {
                out.add(Item.fromMap((Map<String, Object>) o));
            } else {
                out.add(o);
            }
        }
        return out;
    }

    @Override
    public String getWriteableName() {
        return NAME;
    }

    public List<String> fields() {
        return fields;
    }

    public List<Object> like() {
        return like;
    }

    @Override
    protected void doToInnerMap(Map<String, Object> inner) {
        if (!fields.isEmpty()) {
            inner.put("fields", fields);
        }
        inner.put("like", toMapList(like));
        if (!unlike.isEmpty()) {
            inner.put("unlike", toMapList(unlike));
        }
        if (minTermFreq != 2) {
            inner.put("min_term_freq", minTermFreq);
        }
        if (maxQueryTerms != 25) {
            inner.put("max_query_terms", maxQueryTerms);
        }
        if (minDocFreq != 5) {
            inner.put("min_doc_freq", minDocFreq);
        }
        if (maxDocFreq != Integer.MAX_VALUE) {
            inner.put("max_doc_freq", maxDocFreq);
        }
        if (minWordLength != 0) {
            inner.put("min_word_length", minWordLength);
        }
        if (maxWordLength != 0) {
            inner.put("max_word_length", maxWordLength);
        }
        if (!stopWords.isEmpty()) {
            inner.put("stop_words", stopWords);
        }
        if (analyzer != null) {
            inner.put("analyzer", analyzer);
        }
        if (minimumShouldMatch != null) {
            inner.put("minimum_should_match", minimumShouldMatch.asString());
        }
        if (boostTerms != null) {
            inner.put("boost_terms", boostTerms);
        }
        if (include) {
            inner.put("include", true);
        }
        if (!failOnUnsupportedField) {
            inner.put("fail_on_unsupported_field", false);
        }
        writeCommon(inner);
    }

    private static List<Object> toMapList(List<Object> items) {
        List<Object> out = new ArrayList<>();
        for (Object o : items) {
            out.add(o instanceof Item item ? item.toMap() : o);
        }
        return out;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof MoreLikeThisQueryBuilder other)) {
            return false;
        }
        return commonEquals(other) && fields.equals(other.fields) && like.equals(other.like) && unlike.equals(other.unlike)
            && minTermFreq == other.minTermFreq && maxQueryTerms == other.maxQueryTerms && minDocFreq == other.minDocFreq
            && maxDocFreq == other.maxDocFreq && minWordLength == other.minWordLength && maxWordLength == other.maxWordLength
            && stopWords.equals(other.stopWords) && Objects.equals(analyzer, other.analyzer)
            && Objects.equals(minimumShouldMatch, other.minimumShouldMatch) && Objects.equals(boostTerms, other.boostTerms)
            && include == other.include && failOnUnsupportedField == other.failOnUnsupportedField;
    }

    @Override
    public int hashCode() {
        return Objects.hash(commonHash(), fields, like, unlike, minTermFreq, maxQueryTerms, minDocFreq, maxDocFreq,
            minWordLength, maxWordLength, stopWords, analyzer, minimumShouldMatch, boostTerms, include, failOnUnsupportedField);
    }
}
