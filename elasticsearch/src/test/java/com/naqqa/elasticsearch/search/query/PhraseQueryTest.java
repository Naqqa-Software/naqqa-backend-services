package com.naqqa.elasticsearch.search.query;

import com.naqqa.elasticsearch.search.execution.IndexSearcher;
import com.naqqa.elasticsearch.search.execution.ScoreDoc;
import com.naqqa.elasticsearch.search.execution.SimpleLeafReader;
import com.naqqa.elasticsearch.search.execution.TestSegments;
import com.naqqa.elasticsearch.search.execution.TopDocs;
import com.naqqa.elasticsearch.test.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static com.naqqa.elasticsearch.test.Assert.assertEquals;

public final class PhraseQueryTest {

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static IndexSearcher buildSearcher() throws Exception {
        String[] docs = {
            "the quick brown fox",
            "the quick very brown fox",
            "brown quick fox"
        };
        TestSegments.TextField field = TestSegments.buildTextField(3, docs);
        SimpleLeafReader reader = SimpleLeafReader.builder(3)
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
    public void exactPhraseSlopZeroMatchesOnlyAdjacentTerms() throws Exception {
        IndexSearcher searcher = buildSearcher();
        PhraseQuery query = new PhraseQuery("text", List.of(bytes("quick"), bytes("brown")), 0);
        TopDocs topDocs = searcher.search(query, 10);
        assertEquals(Set.of(0), docIds(topDocs));
    }

    @Test
    public void sloppyPhraseToleratesGapWithinSlop() throws Exception {
        IndexSearcher searcher = buildSearcher();
        PhraseQuery query = new PhraseQuery("text", List.of(bytes("quick"), bytes("brown")), 1);
        TopDocs topDocs = searcher.search(query, 10);
        assertEquals(Set.of(0, 1), docIds(topDocs));
    }

    @Test
    public void sloppyPhraseDoesNotMatchReorderedTermsWithinSmallSlop() throws Exception {
        IndexSearcher searcher = buildSearcher();
        PhraseQuery query = new PhraseQuery("text", List.of(bytes("quick"), bytes("brown")), 1);
        TopDocs topDocs = searcher.search(query, 10);
        assertEquals(false, docIds(topDocs).contains(2));
    }

    @Test
    public void largerSlopEventuallyMatchesReorderedTerms() throws Exception {
        IndexSearcher searcher = buildSearcher();
        PhraseQuery query = new PhraseQuery("text", List.of(bytes("quick"), bytes("brown")), 2);
        TopDocs topDocs = searcher.search(query, 10);
        assertEquals(true, docIds(topDocs).contains(2));
    }
}
