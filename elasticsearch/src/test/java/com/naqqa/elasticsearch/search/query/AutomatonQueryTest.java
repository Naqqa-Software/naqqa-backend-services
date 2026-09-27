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

public final class AutomatonQueryTest {

    private static final String[] WORDS = {
        "apple", "apply", "apples", "banana", "grape", "grapefruit", "application"
    };

    private static IndexSearcher buildSearcher() throws Exception {
        TestSegments.TextField field = TestSegments.buildTextField(WORDS.length, WORDS);
        SimpleLeafReader reader = SimpleLeafReader.builder(WORDS.length)
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

    private static Set<Integer> bruteForceStartsWith(String prefix) {
        Set<Integer> result = new TreeSet<>();
        for (int i = 0; i < WORDS.length; i++) {
            if (WORDS[i].startsWith(prefix)) {
                result.add(i);
            }
        }
        return result;
    }

    private static int editDistance(String a, String b) {
        int[][] dp = new int[a.length() + 1][b.length() + 1];
        for (int i = 0; i <= a.length(); i++) {
            dp[i][0] = i;
        }
        for (int j = 0; j <= b.length(); j++) {
            dp[0][j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            for (int j = 1; j <= b.length(); j++) {
                if (a.charAt(i - 1) == b.charAt(j - 1)) {
                    dp[i][j] = dp[i - 1][j - 1];
                } else {
                    dp[i][j] = 1 + Math.min(dp[i - 1][j - 1], Math.min(dp[i - 1][j], dp[i][j - 1]));
                }
            }
        }
        return dp[a.length()][b.length()];
    }

    @Test
    public void wildcardMatchesSameDocsAsBruteForcePrefixCheck() throws Exception {
        IndexSearcher searcher = buildSearcher();
        AutomatonQuery query = AutomatonQuery.wildcard("text", "appl*");
        TopDocs topDocs = searcher.search(query, WORDS.length);
        assertEquals(bruteForceStartsWith("appl"), docIds(topDocs));
    }

    @Test
    public void regexpMatchesSameDocsAsBruteForcePrefixCheck() throws Exception {
        IndexSearcher searcher = buildSearcher();
        AutomatonQuery query = AutomatonQuery.regexp("text", "appl.*");
        TopDocs topDocs = searcher.search(query, WORDS.length);
        assertEquals(bruteForceStartsWith("appl"), docIds(topDocs));
    }

    @Test
    public void fuzzyMatchesSameDocsAsBruteForceEditDistanceCheck() throws Exception {
        IndexSearcher searcher = buildSearcher();
        AutomatonQuery query = AutomatonQuery.fuzzy("text", "apple", 1, 0, false);
        TopDocs topDocs = searcher.search(query, WORDS.length);

        Set<Integer> expected = new TreeSet<>();
        for (int i = 0; i < WORDS.length; i++) {
            if (editDistance("apple", WORDS[i]) <= 1) {
                expected.add(i);
            }
        }
        assertEquals(expected, docIds(topDocs));
    }

    @Test
    public void prefixQueryMatchesSameDocsAsWildcard() throws Exception {
        IndexSearcher searcher = buildSearcher();
        PrefixQuery query = new PrefixQuery("text", "appl".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        TopDocs topDocs = searcher.search(query, WORDS.length);
        assertEquals(bruteForceStartsWith("appl"), docIds(topDocs));
    }
}
