package com.naqqa.elasticsearch.search.bridge.span;

import com.naqqa.elasticsearch.search.execution.IndexSearcher;
import com.naqqa.elasticsearch.search.execution.ScoreDoc;
import com.naqqa.elasticsearch.search.execution.SimpleLeafReader;
import com.naqqa.elasticsearch.search.execution.TestSegments;
import com.naqqa.elasticsearch.search.execution.TopDocs;
import com.naqqa.elasticsearch.search.query.AutomatonQuery;
import com.naqqa.elasticsearch.search.query.Term;
import com.naqqa.elasticsearch.test.Test;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static com.naqqa.elasticsearch.test.Assert.assertEquals;

public final class SpanQueriesTest {

    private static final String[] TEXT = {
        "the quick brown fox jumps over the lazy dog",
        "the lazy dog sleeps quick brown fox near",
        "quick fox brown quick",
        "completely unrelated content here",
        "quick fox"
    };

    private static IndexSearcher buildSearcher() throws Exception {
        int maxDoc = TEXT.length;
        TestSegments.TextField field = TestSegments.buildTextField(maxDoc, TEXT);
        SimpleLeafReader reader = SimpleLeafReader.builder(maxDoc)
            .field("text", field.fieldInfo)
            .terms("text", field.terms, field.docCount)
            .norms("text", field.norms)
            .build();
        return new IndexSearcher(List.of(reader));
    }

    private static Set<Integer> docIds(TopDocs topDocs) {
        Set<Integer> ids = new TreeSet<>();
        for (ScoreDoc sd : topDocs.scoreDocs()) {
            ids.add(sd.doc);
        }
        return ids;
    }

    private static SpanTermQuery term(String value) {
        return new SpanTermQuery(new Term("text", value));
    }

    @Test
    public void spanTermMatchesDocsContainingTerm() throws Exception {
        IndexSearcher searcher = buildSearcher();
        TopDocs topDocs = searcher.search(term("quick"), 10);
        assertEquals(Set.of(0, 1, 2, 4), docIds(topDocs));
    }

    @Test
    public void spanNearInOrderZeroSlopRequiresAdjacency() throws Exception {
        IndexSearcher searcher = buildSearcher();
        SpanNearQuery near = new SpanNearQuery(List.of(term("quick"), term("brown")), 0, true);
        TopDocs topDocs = searcher.search(near, 10);
        assertEquals(Set.of(0, 1), docIds(topDocs));
    }

    @Test
    public void spanNearSlopTooSmallExcludesMatch() throws Exception {
        IndexSearcher searcher = buildSearcher();
        SpanNearQuery near = new SpanNearQuery(List.of(term("brown"), term("over")), 1, true);
        TopDocs topDocs = searcher.search(near, 10);
        assertEquals(Set.of(), docIds(topDocs));
    }

    @Test
    public void spanNearSlopLargeEnoughAllowsMatch() throws Exception {
        IndexSearcher searcher = buildSearcher();
        SpanNearQuery near = new SpanNearQuery(List.of(term("brown"), term("over")), 2, true);
        TopDocs topDocs = searcher.search(near, 10);
        assertEquals(Set.of(0), docIds(topDocs));
    }

    @Test
    public void spanOrMatchesEitherClause() throws Exception {
        IndexSearcher searcher = buildSearcher();
        SpanOrQuery or = new SpanOrQuery(List.of(term("lazy"), term("sleeps")));
        TopDocs topDocs = searcher.search(or, 10);
        assertEquals(Set.of(0, 1), docIds(topDocs));
    }

    @Test
    public void spanNotExcludesOccurrencesNearExcludeTerm() throws Exception {
        IndexSearcher searcher = buildSearcher();
        SpanNotQuery not = new SpanNotQuery(term("quick"), term("fox"), 1, 1);
        TopDocs topDocs = searcher.search(not, 10);
        assertEquals(Set.of(0, 1, 2), docIds(topDocs));
    }

    @Test
    public void spanFirstRequiresMatchWithinPositionLimit() throws Exception {
        IndexSearcher searcher = buildSearcher();
        SpanFirstQuery first = new SpanFirstQuery(term("the"), 2);
        TopDocs topDocs = searcher.search(first, 10);
        assertEquals(Set.of(0, 1), docIds(topDocs));
    }

    @Test
    public void spanContainingRequiresLittleInsideBig() throws Exception {
        IndexSearcher searcher = buildSearcher();
        SpanNearQuery big = new SpanNearQuery(List.of(term("quick"), term("fox")), 1, true);
        SpanContainingQuery containing = new SpanContainingQuery(term("brown"), big);
        TopDocs topDocs = searcher.search(containing, 10);
        assertEquals(Set.of(0, 1), docIds(topDocs));
    }

    @Test
    public void spanWithinRequiresLittleInsideBig() throws Exception {
        IndexSearcher searcher = buildSearcher();
        SpanNearQuery big = new SpanNearQuery(List.of(term("quick"), term("fox")), 1, true);
        SpanWithinQuery within = new SpanWithinQuery(term("brown"), big);
        TopDocs topDocs = searcher.search(within, 10);
        assertEquals(Set.of(0, 1), docIds(topDocs));
    }

    @Test
    public void spanMultiTermWrapperExpandsWildcardMatches() throws Exception {
        IndexSearcher searcher = buildSearcher();
        AutomatonQuery wildcard = AutomatonQuery.wildcard("text", "qu*", false);
        SpanMultiTermQueryWrapper wrapper = new SpanMultiTermQueryWrapper(wildcard, "text");
        TopDocs topDocs = searcher.search(wrapper, 10);
        assertEquals(Set.of(0, 1, 2, 4), docIds(topDocs));
    }

    @Test
    public void fieldMaskingSpanQueryDelegatesToInnerSpans() throws Exception {
        IndexSearcher searcher = buildSearcher();
        FieldMaskingSpanQuery masked = new FieldMaskingSpanQuery(term("quick"), "other_field");
        TopDocs topDocs = searcher.search(masked, 10);
        assertEquals(Set.of(0, 1, 2, 4), docIds(topDocs));
        assertEquals("other_field", masked.field());
    }
}
