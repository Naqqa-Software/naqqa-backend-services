package com.naqqa.elasticsearch.search.advanced.requests;

import com.naqqa.elasticsearch.codec.fieldinfos.DocValuesType;
import com.naqqa.elasticsearch.codec.fieldinfos.FieldInfo;
import com.naqqa.elasticsearch.codec.postings.PostingsFlags;
import com.naqqa.elasticsearch.search.execution.IndexSearcher;
import com.naqqa.elasticsearch.search.execution.SimpleLeafReader;
import com.naqqa.elasticsearch.search.execution.TestSegments;
import com.naqqa.elasticsearch.search.execution.TopDocs;
import com.naqqa.elasticsearch.search.query.Term;
import com.naqqa.elasticsearch.search.query.TermQuery;
import com.naqqa.elasticsearch.search.similarity.Explanation;
import com.naqqa.elasticsearch.test.Test;

import java.util.List;
import java.util.Map;

import static com.naqqa.elasticsearch.test.Assert.assertEquals;
import static com.naqqa.elasticsearch.test.Assert.assertFalse;
import static com.naqqa.elasticsearch.test.Assert.assertTrue;

public final class RequestsMiscTest {

    private static IndexSearcher buildSearcher(int maxDoc, String[] docs) throws Exception {
        TestSegments.TextField field = TestSegments.buildTextField(maxDoc, docs);
        SimpleLeafReader reader = SimpleLeafReader.builder(maxDoc)
            .field("text", field.fieldInfo)
            .terms("text", field.terms, field.docCount)
            .norms("text", field.norms)
            .build();
        return new IndexSearcher(List.of(reader));
    }

    @Test
    public void countMatchesNumberOfDocsContainingTerm() throws Exception {
        IndexSearcher searcher = buildSearcher(10, new String[]{"a b", "a c", "b c", "a b c", "z", "a", "a", "b", "c", "a"});
        long count = SearchCount.count(searcher, new TermQuery(new Term("text", "a")));
        assertEquals(6L, count);
    }

    @Test
    public void validateQueryReportsValidForWellFormedQueryAndInvalidForMalformed() {
        ValidateQuery.Result valid = ValidateQuery.validate(Map.of("term", Map.of("text", "a")), null);
        assertTrue(valid.valid());

        ValidateQuery.Result invalid = ValidateQuery.validate(Map.of("not_a_real_query", Map.of()), null);
        assertFalse(invalid.valid());
        assertTrue(invalid.error() != null);
    }

    @Test
    public void explainRendererProducesValueDescriptionAndDetails() throws Exception {
        IndexSearcher searcher = buildSearcher(3, new String[]{"needle hay", "hay hay", "needle needle"});
        Explanation explanation = searcher.explain(new TermQuery(new Term("text", "needle")), 0);
        Map<String, Object> rendered = ExplainRenderer.render(explanation);
        assertTrue((Boolean) rendered.get("match"));
        assertTrue(rendered.containsKey("value"));
        assertTrue(rendered.containsKey("description"));
        assertTrue(rendered.get("details") instanceof List<?>);
    }

    @Test
    public void fieldCapsAggregatesAcrossShards() {
        FieldInfo textField = new FieldInfo("title", 0, true, PostingsFlags.POSITIONS, true, false, false,
            DocValuesType.NONE, 0, 0, Map.of());
        FieldInfo keywordField = new FieldInfo("category", 1, true, PostingsFlags.DOCS_ONLY, false, false, false,
            DocValuesType.SORTED, 0, 0, Map.of());
        Map<String, List<FieldInfo>> perShard = Map.of(
            "shard0", List.of(textField, keywordField),
            "shard1", List.of(keywordField));

        Map<String, FieldCaps.Capability> caps = FieldCaps.compute(perShard);
        assertEquals("text", caps.get("title").type());
        assertTrue(caps.get("title").searchable());
        assertFalse(caps.get("title").aggregatable());
        assertEquals("keyword", caps.get("category").type());
        assertTrue(caps.get("category").aggregatable());
        assertEquals(2, caps.get("category").indices().size());
    }

    @Test
    public void termsEnumListsTermsByPrefix() throws Exception {
        IndexSearcher searcher = buildSearcher(4, new String[]{"apple apricot", "banana", "apple", "avocado"});
        List<TermsEnumLister.TermCount> terms = TermsEnumLister.list(searcher.leafContexts().get(0).reader(), "text", "ap", 10);
        List<String> names = terms.stream().map(TermsEnumLister.TermCount::term).sorted().toList();
        assertEquals(List.of("apple", "apricot"), names);
    }

    @Test
    public void multiSearchExecutesIndependentRequestsAndCapturesFailures() throws Exception {
        IndexSearcher searcher = buildSearcher(3, new String[]{"needle hay", "hay hay", "needle needle"});
        MultiSearch.SearchRequest good = new MultiSearch.SearchRequest(searcher, new TermQuery(new Term("text", "needle")), 10);
        List<MultiSearch.SearchResult> results = MultiSearch.execute(List.of(good));
        assertEquals(1, results.size());
        assertTrue(results.get(0).error() == null);
        assertEquals(2L, results.get(0).topDocs().totalHits().value());
    }

    @Test
    public void searchTemplateRendersMustacheParamsIntoAQuery() {
        String template = "{\"query\":{\"term\":{\"text\":\"{{value}}\"}}}";
        Map<String, Object> rendered = SearchTemplate.renderToMap(template, Map.of("value", "needle"));
        assertEquals(Map.of("term", Map.of("text", "needle")), rendered.get("query"));
        var sourceBuilder = SearchTemplate.searchTemplate(template, Map.of("value", "needle"));
        assertTrue(sourceBuilder.query() != null);
    }
}
