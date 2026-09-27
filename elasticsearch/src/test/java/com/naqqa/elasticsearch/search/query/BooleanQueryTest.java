package com.naqqa.elasticsearch.search.query;

import com.naqqa.elasticsearch.search.execution.IndexSearcher;
import com.naqqa.elasticsearch.search.execution.ScoreDoc;
import com.naqqa.elasticsearch.search.execution.SimpleLeafReader;
import com.naqqa.elasticsearch.search.execution.TestSegments;
import com.naqqa.elasticsearch.search.execution.TopDocs;
import com.naqqa.elasticsearch.test.Test;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static com.naqqa.elasticsearch.test.Assert.assertEquals;

public final class BooleanQueryTest {

    private static IndexSearcher buildSearcher() throws Exception {
        String[] docs = {
            "apple banana",
            "apple cherry",
            "banana cherry",
            "apple banana cherry",
            "date"
        };
        TestSegments.TextField field = TestSegments.buildTextField(5, docs);
        SimpleLeafReader reader = SimpleLeafReader.builder(5)
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

    @Test
    public void mustAndMustNotFilterCorrectDocSet() throws Exception {
        IndexSearcher searcher = buildSearcher();
        BooleanQuery query = BooleanQuery.builder()
            .add(new TermQuery(new Term("text", "apple")), BooleanQuery.Occur.MUST)
            .add(new TermQuery(new Term("text", "cherry")), BooleanQuery.Occur.MUST_NOT)
            .build();
        TopDocs topDocs = searcher.search(query, 10);
        assertEquals(Set.of(0), docIds(topDocs));
    }

    @Test
    public void minimumShouldMatchCountsCorrectly() throws Exception {
        IndexSearcher searcher = buildSearcher();
        BooleanQuery query = BooleanQuery.builder()
            .add(new TermQuery(new Term("text", "apple")), BooleanQuery.Occur.SHOULD)
            .add(new TermQuery(new Term("text", "banana")), BooleanQuery.Occur.SHOULD)
            .setMinimumShouldMatch(2)
            .build();
        TopDocs topDocs = searcher.search(query, 10);
        assertEquals(Set.of(0, 3), docIds(topDocs));
    }

    @Test
    public void filterClauseGatesWithoutScoringShouldIsOptionalBoost() throws Exception {
        IndexSearcher searcher = buildSearcher();
        BooleanQuery query = BooleanQuery.builder()
            .add(new TermQuery(new Term("text", "banana")), BooleanQuery.Occur.FILTER)
            .add(new TermQuery(new Term("text", "apple")), BooleanQuery.Occur.SHOULD)
            .build();
        TopDocs topDocs = searcher.search(query, 10);
        assertEquals(Set.of(0, 2, 3), docIds(topDocs));

        float scoreDoc0or3 = 0f;
        float scoreDoc2 = 0f;
        for (ScoreDoc sd : topDocs.scoreDocs()) {
            if (sd.doc == 2) {
                scoreDoc2 = sd.score;
            } else {
                scoreDoc0or3 = Math.max(scoreDoc0or3, sd.score);
            }
        }
        assertEquals(0f, scoreDoc2, 1e-6);
        assertEquals(true, scoreDoc0or3 > 0f);
    }

    @Test
    public void pureShouldRequiresAtLeastOneMatch() throws Exception {
        IndexSearcher searcher = buildSearcher();
        BooleanQuery query = BooleanQuery.builder()
            .add(new TermQuery(new Term("text", "apple")), BooleanQuery.Occur.SHOULD)
            .add(new TermQuery(new Term("text", "date")), BooleanQuery.Occur.SHOULD)
            .build();
        TopDocs topDocs = searcher.search(query, 10);
        assertEquals(Set.of(0, 1, 3, 4), docIds(topDocs));
    }
}
