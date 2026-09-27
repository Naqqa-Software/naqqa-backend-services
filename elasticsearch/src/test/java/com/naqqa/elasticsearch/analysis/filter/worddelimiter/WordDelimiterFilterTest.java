package com.naqqa.elasticsearch.analysis.filter.worddelimiter;

import com.naqqa.elasticsearch.analysis.Token;
import com.naqqa.elasticsearch.analysis.TokenStream;
import com.naqqa.elasticsearch.test.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.naqqa.elasticsearch.analysis.filter.worddelimiter.WordDelimiterFilter.*;
import static com.naqqa.elasticsearch.analysis.filter.worddelimiter.WdAssert.assertTerms;
import static com.naqqa.elasticsearch.analysis.filter.worddelimiter.WdAssert.assertTokens;
import static com.naqqa.elasticsearch.test.Assert.assertEquals;

public class WordDelimiterFilterTest {

    private static final byte[] TABLE = WordDelimiterIterator.DEFAULT_WORD_DELIM_TABLE;
    private static final int OFFSET_FLAGS = GENERATE_WORD_PARTS | GENERATE_NUMBER_PARTS | CATENATE_ALL | SPLIT_ON_CASE_CHANGE
        | SPLIT_ON_NUMERICS | STEM_ENGLISH_POSSESSIVE;

    private static TokenStream canned(String term, int start, int end) {
        return new TestCannedTokenStream(new Token(term, start, end));
    }

    private static TokenStream keyword(String text) {
        return canned(text, 0, text.length());
    }

    private static TokenStream ws(String text, int flags, Set<String> prot) {
        return new WordDelimiterFilter(new TestWhitespaceTokenizer(text), TABLE, flags, prot);
    }

    private static TokenStream es(String text, Map<String, Object> params) {
        return new WordDelimiterFilter(new TestWhitespaceTokenizer(text), TABLE, WordDelimiterSettings.flags(params), null);
    }

    @Test
    public void offsets() {
        assertTokens(new WordDelimiterFilter(canned("foo-bar", 5, 12), TABLE, OFFSET_FLAGS, null),
            new String[] {"foo", "foobar", "bar"}, new int[] {5, 5, 9}, new int[] {8, 12, 12}, null, null);
        assertTokens(new WordDelimiterFilter(canned("foo-bar", 5, 6), TABLE, OFFSET_FLAGS, null),
            new String[] {"foo", "bar", "foobar"}, new int[] {5, 5, 5}, new int[] {6, 6, 6}, null, null);
    }

    @Test
    public void offsetChanges() {
        assertTokens(new WordDelimiterFilter(canned("übelkeit)", 7, 16), TABLE, OFFSET_FLAGS, null),
            new String[] {"übelkeit"}, new int[] {7}, new int[] {15}, null, null);
        assertTokens(new WordDelimiterFilter(canned("(übelkeit", 7, 17), TABLE, OFFSET_FLAGS, null),
            new String[] {"übelkeit"}, new int[] {8}, new int[] {17}, null, null);
        assertTokens(new WordDelimiterFilter(canned("(übelkeit", 7, 16), TABLE, OFFSET_FLAGS, null),
            new String[] {"übelkeit"}, new int[] {8}, new int[] {16}, null, null);
        assertTokens(new WordDelimiterFilter(canned("(foo,bar)", 7, 16), TABLE, OFFSET_FLAGS, null),
            new String[] {"foo", "foobar", "bar"}, new int[] {8, 8, 12}, new int[] {11, 15, 15}, null, null);
    }

    private static void doSplit(String input, String... output) {
        int flags = GENERATE_WORD_PARTS | GENERATE_NUMBER_PARTS | SPLIT_ON_CASE_CHANGE | SPLIT_ON_NUMERICS | STEM_ENGLISH_POSSESSIVE;
        assertTerms(new WordDelimiterFilter(keyword(input), TABLE, flags, null), output);
    }

    @Test
    public void splits() {
        doSplit("basic-split", "basic", "split");
        doSplit("camelCase", "camel", "Case");
        doSplit("บ้าน", "บ้าน");
        doSplit("test's'", "test");
        doSplit("Роберт", "Роберт");
        doSplit("РобЕрт", "Роб", "Ерт");
        doSplit("aǅungla", "aǅungla");
        doSplit("ســـــــــــــــــلام", "ســـــــــــــــــلام");
        doSplit("test⃝", "test⃝");
        doSplit("हिन्दी", "हिन्दी");
        doSplit("١٢٣٤", "١٢٣٤");
        doSplit("𠀀𠀀", "𠀀𠀀");
    }

    @Test
    public void possessives() {
        int flags = GENERATE_WORD_PARTS | GENERATE_NUMBER_PARTS | SPLIT_ON_CASE_CHANGE | SPLIT_ON_NUMERICS;
        assertTerms(new WordDelimiterFilter(keyword("ra's"), flags | STEM_ENGLISH_POSSESSIVE, null), "ra");
        assertTerms(new WordDelimiterFilter(keyword("ra's"), flags, null), "ra", "s");
    }

    @Test
    public void positionIncrements() {
        Set<String> prot = Set.of("NUTCH");
        assertTokens(ws("LUCENE / SOLR", OFFSET_FLAGS, prot), new String[] {"LUCENE", "SOLR"},
            new int[] {0, 9}, new int[] {6, 13}, new int[] {1, 1}, null);
        assertTokens(ws("LUCENE / solR", OFFSET_FLAGS, prot), new String[] {"LUCENE", "sol", "solR", "R"},
            new int[] {0, 9, 9, 12}, new int[] {6, 12, 13, 13}, new int[] {1, 1, 0, 1}, null);
        assertTokens(ws("LUCENE / NUTCH SOLR", OFFSET_FLAGS, prot), new String[] {"LUCENE", "NUTCH", "SOLR"},
            new int[] {0, 9, 15}, new int[] {6, 14, 19}, new int[] {1, 1, 1}, null);

        assertTokens(large("LUCENE largegap SOLR", prot), new String[] {"LUCENE", "largegap", "SOLR"},
            new int[] {0, 7, 16}, new int[] {6, 15, 20}, new int[] {1, 10, 1}, null);
        assertTokens(large("LUCENE / SOLR", prot), new String[] {"LUCENE", "SOLR"},
            new int[] {0, 9}, new int[] {6, 13}, new int[] {1, 11}, null);
        assertTokens(large("LUCENE / solR", prot), new String[] {"LUCENE", "sol", "solR", "R"},
            new int[] {0, 9, 9, 12}, new int[] {6, 12, 13, 13}, new int[] {1, 11, 0, 1}, null);
        assertTokens(large("LUCENE / NUTCH SOLR", prot), new String[] {"LUCENE", "NUTCH", "SOLR"},
            new int[] {0, 9, 15}, new int[] {6, 14, 19}, new int[] {1, 11, 1}, null);

        assertTokens(stop("lucene.solr", prot), new String[] {"lucene", "lucenesolr", "solr"},
            new int[] {0, 0, 7}, new int[] {6, 11, 11}, new int[] {1, 0, 1}, null);
        assertTokens(stop("the lucene.solr", prot), new String[] {"lucene", "lucenesolr", "solr"},
            new int[] {4, 4, 11}, new int[] {10, 15, 15}, new int[] {2, 0, 1}, null);
    }

    private static TokenStream large(String text, Set<String> prot) {
        return new WordDelimiterFilter(new TestHelperFilters.LargePosInc(new TestWhitespaceTokenizer(text)), TABLE, OFFSET_FLAGS, prot);
    }

    private static TokenStream stop(String text, Set<String> prot) {
        return new WordDelimiterFilter(new TestHelperFilters.Stop(new TestWhitespaceTokenizer(text), Set.of("the")), TABLE, OFFSET_FLAGS, prot);
    }

    @Test
    public void keywordFilter() {
        assertTerms(new WordDelimiterFilter(new TestHelperFilters.KeywordStartsWithK(new TestWhitespaceTokenizer("abc-def klm-nop kpop")),
            GENERATE_WORD_PARTS, null), "abc", "def", "klm", "nop", "kpop");
        assertTokens(new WordDelimiterFilter(new TestHelperFilters.KeywordStartsWithK(new TestWhitespaceTokenizer("abc-def klm-nop kpop")),
                GENERATE_WORD_PARTS | IGNORE_KEYWORDS, null),
            new String[] {"abc", "def", "klm-nop", "kpop"}, new int[] {0, 4, 8, 16}, new int[] {3, 7, 15, 20}, new int[] {1, 1, 1, 1}, null);
    }

    @Test
    public void lotsOfConcatenating() {
        int flags = GENERATE_WORD_PARTS | GENERATE_NUMBER_PARTS | CATENATE_WORDS | CATENATE_NUMBERS | CATENATE_ALL
            | SPLIT_ON_CASE_CHANGE | SPLIT_ON_NUMERICS | STEM_ENGLISH_POSSESSIVE;
        assertTokens(ws("abc-def-123-456", flags, null),
            new String[] {"abc", "abcdef", "abcdef123456", "def", "123", "123456", "456"},
            new int[] {0, 0, 0, 4, 8, 8, 12}, new int[] {3, 7, 15, 7, 11, 15, 15},
            new int[] {1, 0, 0, 1, 1, 0, 1}, null);
    }

    @Test
    public void lotsOfConcatenating2() {
        int flags = PRESERVE_ORIGINAL | GENERATE_WORD_PARTS | GENERATE_NUMBER_PARTS | CATENATE_WORDS | CATENATE_NUMBERS | CATENATE_ALL
            | SPLIT_ON_CASE_CHANGE | SPLIT_ON_NUMERICS | STEM_ENGLISH_POSSESSIVE;
        assertTokens(ws("abc-def-123-456", flags, null),
            new String[] {"abc-def-123-456", "abc", "abcdef", "abcdef123456", "def", "123", "123456", "456"},
            new int[] {0, 0, 0, 0, 4, 8, 8, 12}, new int[] {15, 3, 7, 15, 7, 11, 15, 15},
            new int[] {1, 0, 0, 0, 1, 1, 0, 1}, null);
    }

    @Test
    public void onlyNumbersAndNumberPunct() {
        int flags = GENERATE_WORD_PARTS | SPLIT_ON_CASE_CHANGE | SPLIT_ON_NUMERICS;
        assertTokens(ws("7-586", flags, null), new String[] {}, new int[] {}, new int[] {}, new int[] {}, null);
        assertTokens(ws("6-", flags, null), new String[] {"6"}, new int[] {0}, new int[] {1}, new int[] {1}, null);
    }

    @Test
    public void emptyTermAllFlags() {
        for (int flags = 0; flags < 1024; flags++) {
            List<Token> out = WdAssert.collect(new WordDelimiterFilter(keyword(""), flags, null));
            assertEquals((flags & PRESERVE_ORIGINAL) != 0 ? 1 : 0, out.size());
            List<Token> graph = WdAssert.collect(new WordDelimiterGraphFilter(keyword(""), flags, null));
            assertEquals((flags & PRESERVE_ORIGINAL) != 0 ? 1 : 0, graph.size());
        }
    }

    @Test
    public void esDocsDefault() {
        assertTokens(es("Neil's-Super-Duper-XL500--42+AutoCoder", Map.of()),
            new String[] {"Neil", "Super", "Duper", "XL", "500", "42", "Auto", "Coder"},
            new int[] {0, 7, 13, 19, 21, 26, 29, 33}, new int[] {4, 12, 18, 21, 24, 28, 33, 38},
            new int[] {1, 1, 1, 1, 1, 1, 1, 1}, null);
    }

    @Test
    public void esExamples() {
        assertTokens(es("the wi-fi is enabled", Map.of("catenate_words", true)),
            new String[] {"the", "wi", "wifi", "fi", "is", "enabled"},
            new int[] {0, 4, 4, 7, 10, 13}, new int[] {3, 6, 9, 9, 12, 20}, new int[] {1, 1, 0, 1, 1, 1}, null);
        assertTokens(es("500-42", Map.of("catenate_numbers", true)),
            new String[] {"500", "50042", "42"}, new int[] {0, 0, 4}, new int[] {3, 6, 6}, new int[] {1, 0, 1}, null);
        assertTokens(es("500-42", Map.of("preserve_original", true)),
            new String[] {"500-42", "500", "42"}, new int[] {0, 0, 4}, new int[] {6, 3, 6}, new int[] {1, 0, 1}, null);
        assertTerms(es("PowerShot", Map.of()), "Power", "Shot");
        assertTerms(es("SD500", Map.of()), "SD", "500");
        assertTerms(es("j2se", Map.of()), "j", "2", "se");
        assertTerms(es("wi-fi-4000", Map.of("catenate_all", true)), "wi", "wifi4000", "fi", "4000");
    }

    @Test
    public void protectedWordsKeptWhole() {
        assertTerms(ws("foo-bar baz-qux", GENERATE_WORD_PARTS, WordDelimiterSettings.protectedWords(List.of("foo-bar"))),
            "foo-bar", "baz", "qux");
    }

    @Test
    public void customTypeTable() {
        byte[] table = WordDelimiterSettings.parseTypeTable(List.of("$ => DIGIT", "% => DIGIT", ". => DIGIT", "\\u002C => DIGIT"));
        int flags = WordDelimiterSettings.flags(Map.of());
        assertTerms(new WordDelimiterFilter(new TestWhitespaceTokenizer("$1,000.00 50%"), table, flags, null), "$1,000.00", "50%");
        assertTerms(new WordDelimiterFilter(new TestWhitespaceTokenizer("$1,000.00 50%"), TABLE, flags, null), "1", "000", "00", "50");
    }
}
