package com.naqqa.elasticsearch.search.query;

import com.naqqa.elasticsearch.search.execution.IndexSearcher;
import com.naqqa.elasticsearch.search.execution.ScoreDoc;
import com.naqqa.elasticsearch.search.execution.SimpleLeafReader;
import com.naqqa.elasticsearch.search.execution.TestSegments;
import com.naqqa.elasticsearch.search.execution.TopDocs;
import com.naqqa.elasticsearch.search.execution.TopScoreDocCollector;
import com.naqqa.elasticsearch.test.Test;

import java.util.List;
import java.util.Random;

import static com.naqqa.elasticsearch.test.Assert.assertEquals;

public final class WandScorerTest {

    private static final String[] TERMS = {"alpha", "beta", "gamma", "delta", "epsilon"};

    private static IndexSearcher buildRandomCorpus(long seed, int maxDoc) throws Exception {
        Random random = new Random(seed);
        String[] docs = new String[maxDoc];
        for (int d = 0; d < maxDoc; d++) {
            StringBuilder sb = new StringBuilder();
            int numWords = 3 + random.nextInt(10);
            for (int w = 0; w < numWords; w++) {
                sb.append(TERMS[random.nextInt(TERMS.length)]).append(' ');
            }
            docs[d] = sb.toString();
        }
        TestSegments.TextField field = TestSegments.buildTextField(maxDoc, docs);
        SimpleLeafReader reader = SimpleLeafReader.builder(maxDoc)
            .field("text", field.fieldInfo)
            .terms("text", field.terms, field.docCount)
            .norms("text", field.norms)
            .build();
        return new IndexSearcher(List.of(reader));
    }

    private static BooleanQuery disjunctionOf(String... terms) {
        BooleanQuery.Builder builder = BooleanQuery.builder();
        for (String t : terms) {
            builder.add(new TermQuery(new Term("text", t)), BooleanQuery.Occur.SHOULD);
        }
        return builder.build();
    }

    @Test
    public void wandEarlyTerminationMatchesExhaustiveTopK() throws Exception {
        for (long seed = 0; seed < 8; seed++) {
            IndexSearcher searcher = buildRandomCorpus(seed, 400);
            BooleanQuery query = disjunctionOf("alpha", "beta", "gamma", "delta");
            int topN = 10;

            TopDocs exhaustive = searcher.search(query, topN, TopScoreDocCollector.TRACK_TOTAL_HITS_ACCURATE);
            TopDocs wandOptimized = searcher.search(query, topN, 1);

            assertEquals(exhaustive.scoreDocs().length, wandOptimized.scoreDocs().length);
            for (int i = 0; i < exhaustive.scoreDocs().length; i++) {
                ScoreDoc a = exhaustive.scoreDocs()[i];
                ScoreDoc b = wandOptimized.scoreDocs()[i];
                assertEquals(a.doc, b.doc);
                assertEquals(a.score, b.score, 1e-4);
            }
        }
    }
}
