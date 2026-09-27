package com.naqqa.elasticsearch.search.bridge;

import com.naqqa.elasticsearch.codec.fieldinfos.DocValuesType;
import com.naqqa.elasticsearch.codec.fieldinfos.FieldInfo;
import com.naqqa.elasticsearch.codec.postings.PostingsFlags;
import com.naqqa.elasticsearch.ingest.json.IngestJsonParser;
import com.naqqa.elasticsearch.index.query.QueryBuilder;
import com.naqqa.elasticsearch.index.query.QueryParser;
import com.naqqa.elasticsearch.search.advanced.common.QueryBuilderToQuery;
import com.naqqa.elasticsearch.search.execution.IndexSearcher;
import com.naqqa.elasticsearch.search.execution.ScoreDoc;
import com.naqqa.elasticsearch.search.execution.SimpleLeafReader;
import com.naqqa.elasticsearch.search.execution.TestSegments;
import com.naqqa.elasticsearch.search.execution.TopDocs;
import com.naqqa.elasticsearch.search.query.Query;
import com.naqqa.elasticsearch.test.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static com.naqqa.elasticsearch.test.Assert.assertEquals;
import static com.naqqa.elasticsearch.test.Assert.assertTrue;

public final class BridgeQueriesTest {

    private static final String[] BODY = {
        "quick brown fox jumps",
        "quick brown fox runs fast",
        "lazy dog sleeps",
        "brown fox eats quickly"
    };

    private static final String[] TITLE = {
        "fox tales",
        "daily quick news",
        "lazy tales",
        "brown legends"
    };

    private static final String[] IDS = {"1", "2", "3", "4"};

    private static IndexSearcher buildSearcher() throws Exception {
        int maxDoc = BODY.length;
        TestSegments.TextField bodyField = TestSegments.buildTextField(maxDoc, BODY);
        TestSegments.TextField titleField = TestSegments.buildTextField(maxDoc, TITLE);
        TestSegments.TextField idField = TestSegments.buildTextField(maxDoc, IDS);
        long[] price = {10L, 20L, 30L, 40L};
        boolean[] present = {true, true, false, true};
        var priceDv = TestSegments.buildNumericDocValues(maxDoc, price, present);
        var pricePoints = TestSegments.buildLongPoints(maxDoc, price, present);
        FieldInfo priceInfo = new FieldInfo("price", 1, true, PostingsFlags.DOCS_ONLY, false, false, false,
            DocValuesType.NUMERIC, 1, 8, Map.of());
        SimpleLeafReader reader = SimpleLeafReader.builder(maxDoc)
            .field("body", bodyField.fieldInfo)
            .terms("body", bodyField.terms, bodyField.docCount)
            .norms("body", bodyField.norms)
            .field("title", titleField.fieldInfo)
            .terms("title", titleField.terms, titleField.docCount)
            .norms("title", titleField.norms)
            .field("_id", idField.fieldInfo)
            .terms("_id", idField.terms, idField.docCount)
            .norms("_id", idField.norms)
            .field("price", priceInfo)
            .numericDocValues("price", priceDv)
            .points("price", pricePoints)
            .build();
        return new IndexSearcher(List.of(reader));
    }

    private static Set<Integer> search(IndexSearcher searcher, String json) throws Exception {
        Object parsed = IngestJsonParser.parse(json);
        @SuppressWarnings("unchecked")
        QueryBuilder builder = QueryParser.parseQuery((Map<String, Object>) parsed);
        Query query = QueryBuilderToQuery.convert(builder);
        TopDocs topDocs = searcher.search(query, 10);
        Set<Integer> ids = new TreeSet<>();
        for (ScoreDoc sd : topDocs.scoreDocs()) {
            ids.add(sd.doc);
        }
        return ids;
    }

    @Test
    public void matchPhraseMatchesAdjacentTerms() throws Exception {
        IndexSearcher searcher = buildSearcher();
        Set<Integer> result = search(searcher, "{\"match_phrase\": {\"body\": \"quick brown\"}}");
        assertEquals(Set.of(0, 1), result);
    }

    @Test
    public void multiMatchBestFieldsMatchesAcrossFields() throws Exception {
        IndexSearcher searcher = buildSearcher();
        Set<Integer> result = search(searcher,
            "{\"multi_match\": {\"query\": \"lazy\", \"fields\": [\"body\", \"title\"], \"type\": \"best_fields\"}}");
        assertEquals(Set.of(2), result);
    }

    @Test
    public void rangeQueryMatchesNumericBounds() throws Exception {
        IndexSearcher searcher = buildSearcher();
        Set<Integer> result = search(searcher, "{\"range\": {\"price\": {\"gte\": 15, \"lte\": 35}}}");
        assertEquals(Set.of(1), result);
    }

    @Test
    public void wildcardMatchesPrefixPattern() throws Exception {
        IndexSearcher searcher = buildSearcher();
        Set<Integer> result = search(searcher, "{\"wildcard\": {\"body\": \"qui*\"}}");
        assertEquals(Set.of(0, 1, 3), result);
    }

    @Test
    public void regexpMatchesFixedLengthPattern() throws Exception {
        IndexSearcher searcher = buildSearcher();
        Set<Integer> result = search(searcher, "{\"regexp\": {\"body\": \"qu.ck\"}}");
        assertEquals(Set.of(0, 1), result);
    }

    @Test
    public void fuzzyMatchesWithinEditDistance() throws Exception {
        IndexSearcher searcher = buildSearcher();
        Set<Integer> result = search(searcher, "{\"fuzzy\": {\"body\": {\"value\": \"quikc\", \"fuzziness\": 2}}}");
        assertEquals(Set.of(0, 1), result);
    }

    @Test
    public void idsQueryMatchesGivenIds() throws Exception {
        IndexSearcher searcher = buildSearcher();
        Set<Integer> result = search(searcher, "{\"ids\": {\"values\": [\"2\", \"4\"]}}");
        assertEquals(Set.of(1, 3), result);
    }

    @Test
    public void disMaxCombinesMultipleQueries() throws Exception {
        IndexSearcher searcher = buildSearcher();
        Set<Integer> result = search(searcher,
            "{\"dis_max\": {\"queries\": [{\"term\": {\"body\": \"lazy\"}}, {\"term\": {\"body\": \"eats\"}}]}}");
        assertEquals(Set.of(2, 3), result);
    }

    @Test
    public void boostingReturnsAllDocsWithNegativeDamping() throws Exception {
        IndexSearcher searcher = buildSearcher();
        Set<Integer> result = search(searcher, "{\"boosting\": {\"positive\": {\"match_all\": {}}, "
            + "\"negative\": {\"term\": {\"body\": \"lazy\"}}, \"negative_boost\": 0.2}}");
        assertEquals(Set.of(0, 1, 2, 3), result);
    }

    @Test
    public void existsQueryOnlyMatchesDocsWithValue() throws Exception {
        IndexSearcher searcher = buildSearcher();
        Set<Integer> result = search(searcher, "{\"exists\": {\"field\": \"price\"}}");
        assertEquals(Set.of(0, 1, 3), result);
    }

    @Test
    public void prefixQueryMatchesTokensStartingWithPrefix() throws Exception {
        IndexSearcher searcher = buildSearcher();
        Set<Integer> result = search(searcher, "{\"prefix\": {\"body\": \"bro\"}}");
        assertEquals(Set.of(0, 1, 3), result);
    }

    @Test
    public void termCaseInsensitiveMatchesRegardlessOfCase() throws Exception {
        IndexSearcher searcher = buildSearcher();
        Set<Integer> result = search(searcher, "{\"term\": {\"_id\": {\"value\": \"2\", \"case_insensitive\": true}}}");
        assertEquals(Set.of(1), result);
    }

    @Test
    public void queryStringBridgesParsedClauseTree() throws Exception {
        IndexSearcher searcher = buildSearcher();
        Set<Integer> result = search(searcher,
            "{\"query_string\": {\"query\": \"quick\", \"default_field\": \"body\"}}");
        assertEquals(Set.of(0, 1), result);
    }

    @Test
    public void pinnedQueryAlwaysIncludesPinnedIds() throws Exception {
        IndexSearcher searcher = buildSearcher();
        Set<Integer> result = search(searcher,
            "{\"pinned\": {\"ids\": [\"3\"], \"organic\": {\"term\": {\"body\": \"quick\"}}}}");
        assertTrue(result.contains(2), "pinned doc should be included even though it does not match organic query");
        assertEquals(Set.of(0, 1, 2), result);
    }
}
