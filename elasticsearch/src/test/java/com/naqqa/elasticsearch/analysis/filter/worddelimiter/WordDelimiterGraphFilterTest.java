package com.naqqa.elasticsearch.analysis.filter.worddelimiter;

import com.naqqa.elasticsearch.analysis.Token;
import com.naqqa.elasticsearch.analysis.TokenStream;
import com.naqqa.elasticsearch.test.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.naqqa.elasticsearch.analysis.filter.worddelimiter.WordDelimiterGraphFilter.*;
import static com.naqqa.elasticsearch.analysis.filter.worddelimiter.WdAssert.assertGraphStrings;
import static com.naqqa.elasticsearch.analysis.filter.worddelimiter.WdAssert.assertTerms;
import static com.naqqa.elasticsearch.analysis.filter.worddelimiter.WdAssert.assertTokens;
import static com.naqqa.elasticsearch.test.Assert.assertEquals;
import static com.naqqa.elasticsearch.test.Assert.assertFalse;
import static com.naqqa.elasticsearch.test.Assert.assertThrows;
import static com.naqqa.elasticsearch.test.Assert.assertTrue;

public class WordDelimiterGraphFilterTest {

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
        return new WordDelimiterGraphFilter(new TestWhitespaceTokenizer(text), true, TABLE, flags, prot);
    }

    private static TokenStream es(String text, Map<String, Object> params) {
        return new WordDelimiterGraphFilter(new TestWhitespaceTokenizer(text), WordDelimiterSettings.adjustOffsets(params),
            TABLE, WordDelimiterSettings.flags(params), null);
    }

    @Test
    public void offsets() {
        assertTokens(new WordDelimiterGraphFilter(canned("foo-bar", 5, 12), true, TABLE, OFFSET_FLAGS, null),
            new String[] {"foobar", "foo", "bar"}, new int[] {5, 5, 9}, new int[] {12, 8, 12}, null, null);
        assertTokens(new WordDelimiterGraphFilter(canned("foo-bar", 5, 6), true, TABLE, OFFSET_FLAGS, null),
            new String[] {"foobar", "foo", "bar"}, new int[] {5, 5, 5}, new int[] {6, 6, 6}, null, null);
    }

    @Test
    public void offsetChanges() {
        assertTokens(new WordDelimiterGraphFilter(canned("übelkeit)", 7, 16), true, TABLE, OFFSET_FLAGS, null),
            new String[] {"übelkeit"}, new int[] {7}, new int[] {15}, null, null);
        assertTokens(new WordDelimiterGraphFilter(canned("(übelkeit", 7, 17), true, TABLE, OFFSET_FLAGS, null),
            new String[] {"übelkeit"}, new int[] {7}, new int[] {17}, null, null);
        assertTokens(new WordDelimiterGraphFilter(canned("(übelkeit", 7, 16), true, TABLE, OFFSET_FLAGS, null),
            new String[] {"übelkeit"}, new int[] {8}, new int[] {16}, null, null);
        assertTokens(new WordDelimiterGraphFilter(canned("(foo,bar)", 7, 16), true, TABLE, OFFSET_FLAGS, null),
            new String[] {"foobar", "foo", "bar"}, new int[] {8, 8, 12}, new int[] {15, 11, 15}, null, null);
    }

    private static void doSplit(String input, String... output) {
        int flags = GENERATE_WORD_PARTS | GENERATE_NUMBER_PARTS | SPLIT_ON_CASE_CHANGE | SPLIT_ON_NUMERICS | STEM_ENGLISH_POSSESSIVE;
        assertTerms(new WordDelimiterGraphFilter(keyword(input), false, TABLE, flags, null), output);
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
        assertTerms(new WordDelimiterGraphFilter(keyword("ra's"), flags | STEM_ENGLISH_POSSESSIVE, null), "ra");
        assertTerms(new WordDelimiterGraphFilter(keyword("ra's"), flags, null), "ra", "s");
    }

    @Test
    public void tokenType() {
        Token t = new Token("foo-bar", 5, 12);
        t.setType("mytype");
        List<Token> out = WdAssert.collect(new WordDelimiterGraphFilter(new TestCannedTokenStream(t), OFFSET_FLAGS, null));
        assertEquals(3, out.size());
        for (Token o : out) {
            assertEquals("mytype", o.type());
        }
    }

    @Test
    public void positionIncrements() {
        int f4 = SPLIT_ON_NUMERICS | GENERATE_WORD_PARTS | PRESERVE_ORIGINAL | GENERATE_NUMBER_PARTS | SPLIT_ON_CASE_CHANGE;
        assertTokens(new WordDelimiterGraphFilter(new TestWhitespaceTokenizer("SAL_S8371 - SAL"), f4, Set.of()),
            new String[] {"SAL_S8371", "SAL", "S", "8371", "-", "SAL"}, null, null, new int[] {1, 0, 1, 1, 1, 1}, null);

        Set<String> prot = Set.of("NUTCH");
        assertTokens(ws("LUCENE / SOLR", OFFSET_FLAGS, prot), new String[] {"LUCENE", "SOLR"},
            new int[] {0, 9}, new int[] {6, 13}, new int[] {1, 2}, null);
        assertTokens(ws("LUCENE / solR", OFFSET_FLAGS, prot), new String[] {"LUCENE", "solR", "sol", "R"},
            new int[] {0, 9, 9, 12}, new int[] {6, 13, 12, 13}, new int[] {1, 2, 0, 1}, null);
        assertTokens(ws("LUCENE / NUTCH SOLR", OFFSET_FLAGS, prot), new String[] {"LUCENE", "NUTCH", "SOLR"},
            new int[] {0, 9, 15}, new int[] {6, 14, 19}, new int[] {1, 2, 1}, null);

        assertTokens(large("LUCENE largegap SOLR", prot), new String[] {"LUCENE", "largegap", "SOLR"},
            new int[] {0, 7, 16}, new int[] {6, 15, 20}, new int[] {1, 10, 1}, null);
        assertTokens(large("LUCENE / SOLR", prot), new String[] {"LUCENE", "SOLR"},
            new int[] {0, 9}, new int[] {6, 13}, new int[] {1, 11}, null);
        assertTokens(large("LUCENE / solR", prot), new String[] {"LUCENE", "solR", "sol", "R"},
            new int[] {0, 9, 9, 12}, new int[] {6, 13, 12, 13}, new int[] {1, 11, 0, 1}, null);
        assertTokens(large("LUCENE / NUTCH SOLR", prot), new String[] {"LUCENE", "NUTCH", "SOLR"},
            new int[] {0, 9, 15}, new int[] {6, 14, 19}, new int[] {1, 11, 1}, null);

        assertTokens(stop("lucene.solr", prot), new String[] {"lucenesolr", "lucene", "solr"},
            new int[] {0, 0, 7}, new int[] {11, 6, 11}, new int[] {1, 0, 1}, new int[] {2, 1, 1});
        assertTokens(stop("the lucene.solr", prot), new String[] {"lucenesolr", "lucene", "solr"},
            new int[] {4, 4, 11}, new int[] {15, 10, 15}, new int[] {2, 0, 1}, null);
    }

    private static TokenStream large(String text, Set<String> prot) {
        return new WordDelimiterGraphFilter(new TestHelperFilters.LargePosInc(new TestWhitespaceTokenizer(text)), true, TABLE, OFFSET_FLAGS, prot);
    }

    private static TokenStream stop(String text, Set<String> prot) {
        return new WordDelimiterGraphFilter(new TestHelperFilters.Stop(new TestWhitespaceTokenizer(text), Set.of("the", "a")),
            true, TABLE, OFFSET_FLAGS, prot);
    }

    @Test
    public void keywordFilter() {
        assertTerms(new WordDelimiterGraphFilter(new TestHelperFilters.KeywordStartsWithK(new TestWhitespaceTokenizer("abc-def klm-nop kpop")),
            GENERATE_WORD_PARTS, null), "abc", "def", "klm", "nop", "kpop");
        assertTokens(new WordDelimiterGraphFilter(new TestHelperFilters.KeywordStartsWithK(new TestWhitespaceTokenizer("abc-def klm-nop kpop")),
                GENERATE_WORD_PARTS | IGNORE_KEYWORDS, null),
            new String[] {"abc", "def", "klm-nop", "kpop"}, new int[] {0, 0, 8, 16}, new int[] {7, 7, 15, 20}, new int[] {1, 1, 1, 1}, null);
    }

    @Test
    public void originalTokenEmittedFirst() {
        int flags = PRESERVE_ORIGINAL | GENERATE_WORD_PARTS | GENERATE_NUMBER_PARTS | CATENATE_WORDS | CATENATE_NUMBERS | CATENATE_ALL
            | SPLIT_ON_CASE_CHANGE | SPLIT_ON_NUMERICS | STEM_ENGLISH_POSSESSIVE;
        assertTerms(ws("abc-def abcDEF abc123", flags, null),
            "abc-def", "abcdef", "abc", "def", "abcDEF", "abcDEF", "abc", "DEF", "abc123", "abc123", "abc", "123");
    }

    @Test
    public void catenateAllEmittedBeforeParts() {
        int flags = PRESERVE_ORIGINAL | GENERATE_WORD_PARTS | CATENATE_ALL;
        assertTokens(ws("8-other", flags, null), new String[] {"8-other", "8other", "other"},
            new int[] {0, 0, 2}, new int[] {7, 7, 7}, new int[] {1, 0, 0}, null);
        assertTokens(ws("other-9", flags, null), new String[] {"other-9", "other9", "other"},
            new int[] {0, 0, 0}, new int[] {7, 7, 5}, new int[] {1, 0, 0}, null);
    }

    @Test
    public void lotsOfConcatenating() {
        int flags = GENERATE_WORD_PARTS | GENERATE_NUMBER_PARTS | CATENATE_WORDS | CATENATE_NUMBERS | CATENATE_ALL
            | SPLIT_ON_CASE_CHANGE | SPLIT_ON_NUMERICS | STEM_ENGLISH_POSSESSIVE;
        assertTokens(ws("abc-def-123-456", flags, null),
            new String[] {"abcdef123456", "abcdef", "abc", "def", "123456", "123", "456"},
            new int[] {0, 0, 0, 4, 8, 8, 12}, new int[] {15, 7, 3, 7, 15, 11, 15},
            new int[] {1, 0, 0, 1, 1, 0, 1}, new int[] {4, 2, 1, 1, 2, 1, 1});
    }

    @Test
    public void lotsOfConcatenating2() {
        int flags = PRESERVE_ORIGINAL | GENERATE_WORD_PARTS | GENERATE_NUMBER_PARTS | CATENATE_WORDS | CATENATE_NUMBERS | CATENATE_ALL
            | SPLIT_ON_CASE_CHANGE | SPLIT_ON_NUMERICS | STEM_ENGLISH_POSSESSIVE;
        assertTokens(new WordDelimiterGraphFilter(new TestWhitespaceTokenizer("abc-def-123-456"), flags, null),
            new String[] {"abc-def-123-456", "abcdef123456", "abcdef", "abc", "def", "123456", "123", "456"},
            new int[] {0, 0, 0, 0, 0, 0, 0, 0}, new int[] {15, 15, 15, 15, 15, 15, 15, 15},
            new int[] {1, 0, 0, 0, 1, 1, 0, 1}, new int[] {4, 4, 2, 1, 1, 2, 1, 1});
    }

    private static TokenStream kw(int flags, String text) {
        return new WordDelimiterGraphFilter(keyword(text), flags, null);
    }

    @Test
    public void basicGraphSplits() {
        assertGraphStrings(kw(0, "PowerShotPlus"), "PowerShotPlus");
        assertGraphStrings(kw(GENERATE_WORD_PARTS, "PowerShotPlus"), "PowerShotPlus");
        assertGraphStrings(kw(GENERATE_WORD_PARTS | SPLIT_ON_CASE_CHANGE, "PowerShotPlus"), "Power Shot Plus");
        assertGraphStrings(kw(GENERATE_WORD_PARTS | SPLIT_ON_CASE_CHANGE | PRESERVE_ORIGINAL, "PowerShotPlus"), "PowerShotPlus", "Power Shot Plus");
        assertGraphStrings(kw(GENERATE_WORD_PARTS, "Power-Shot-Plus"), "Power Shot Plus");
        assertGraphStrings(kw(GENERATE_WORD_PARTS | SPLIT_ON_CASE_CHANGE, "Power-Shot-Plus"), "Power Shot Plus");
        assertGraphStrings(kw(GENERATE_WORD_PARTS | SPLIT_ON_CASE_CHANGE | PRESERVE_ORIGINAL, "Power-Shot-Plus"), "Power-Shot-Plus", "Power Shot Plus");
        assertGraphStrings(kw(GENERATE_WORD_PARTS | SPLIT_ON_CASE_CHANGE, "PowerShot1000Plus"), "Power Shot1000Plus");
        assertGraphStrings(kw(GENERATE_WORD_PARTS | SPLIT_ON_CASE_CHANGE | CATENATE_WORDS, "PowerShotPlus"), "Power Shot Plus", "PowerShotPlus");
        assertGraphStrings(kw(GENERATE_WORD_PARTS | SPLIT_ON_CASE_CHANGE | CATENATE_WORDS, "PowerShot1000Plus"), "Power Shot1000Plus", "PowerShot1000Plus");
        int f = GENERATE_WORD_PARTS | GENERATE_NUMBER_PARTS | SPLIT_ON_CASE_CHANGE | CATENATE_WORDS | CATENATE_NUMBERS;
        assertGraphStrings(kw(f, "Power-Shot-1000-17-Plus"),
            "Power Shot 1000 17 Plus", "Power Shot 100017 Plus", "PowerShot 1000 17 Plus", "PowerShot 100017 Plus");
        assertGraphStrings(kw(f | PRESERVE_ORIGINAL, "Power-Shot-1000-17-Plus"),
            "Power-Shot-1000-17-Plus", "Power Shot 1000 17 Plus", "Power Shot 100017 Plus", "PowerShot 1000 17 Plus", "PowerShot 100017 Plus");
    }

    @Test
    public void onlyNumbersAndNoCatenate() {
        assertGraphStrings(kw(GENERATE_WORD_PARTS | SPLIT_ON_CASE_CHANGE | SPLIT_ON_NUMERICS, "7-586"));
        assertGraphStrings(kw(GENERATE_WORD_PARTS | GENERATE_NUMBER_PARTS | SPLIT_ON_CASE_CHANGE | SPLIT_ON_NUMERICS, "a-b-c-9-d"), "a b c 9 d");
    }

    @Test
    public void invalidFlag() {
        assertThrows(IllegalArgumentException.class, () -> new WordDelimiterGraphFilter(new TestCannedTokenStream(), 1 << 31, null));
    }

    @Test
    public void emptyString() {
        WordDelimiterGraphFilter wdf = new WordDelimiterGraphFilter(canned("", 0, 0), GENERATE_WORD_PARTS | CATENATE_ALL | PRESERVE_ORIGINAL, null);
        wdf.reset();
        assertTrue(wdf.incrementToken());
        assertFalse(wdf.incrementToken());
        wdf.end();
        wdf.close();
    }

    @Test
    public void protectedWords() {
        TokenStream tokens = new TestCannedTokenStream(new Token("foo17-bar", 0, 9), new Token("foo-bar", 0, 7));
        Set<String> prot = WordDelimiterSettings.protectedWords(List.of("foo17-bar"));
        assertGraphStrings(new WordDelimiterGraphFilter(tokens, GENERATE_WORD_PARTS | PRESERVE_ORIGINAL | CATENATE_ALL, prot),
            "foo17-bar foo bar", "foo17-bar foo-bar", "foo17-bar foobar");
    }

    @Test
    public void curiousCases() {
        SlowWdf.verify("u-0L-4836-ip4Gw--13--q7--L07E1", CATENATE_WORDS | CATENATE_ALL | SPLIT_ON_CASE_CHANGE | SPLIT_ON_NUMERICS | STEM_ENGLISH_POSSESSIVE);
        SlowWdf.verify("u-l-p", CATENATE_ALL);
        SlowWdf.verify("Foo-Bar-Baz", CATENATE_WORDS | SPLIT_ON_CASE_CHANGE | PRESERVE_ORIGINAL);
        SlowWdf.verify("cQzk4-GL0izl0mKM-J8--4m-'s", GENERATE_NUMBER_PARTS | CATENATE_NUMBERS | SPLIT_ON_CASE_CHANGE | SPLIT_ON_NUMERICS);
        SlowWdf.verify("8-other", PRESERVE_ORIGINAL | GENERATE_WORD_PARTS | CATENATE_ALL);
    }

    @Test
    public void randomPaths() {
        java.util.Random random = new java.util.Random(42);
        for (int iter = 0; iter < 3000; iter++) {
            String text = SlowWdf.randomText(random);
            int flags = random.nextInt(512);
            SlowWdf.verify(text, flags);
        }
    }

    @Test
    public void esDocsDefault() {
        assertTokens(es("Neil's-Super-Duper-XL500--42+AutoCoder", Map.of()),
            new String[] {"Neil", "Super", "Duper", "XL", "500", "42", "Auto", "Coder"},
            new int[] {0, 7, 13, 19, 21, 26, 29, 33}, new int[] {4, 12, 18, 21, 24, 28, 33, 38},
            new int[] {1, 1, 1, 1, 1, 1, 1, 1}, new int[] {1, 1, 1, 1, 1, 1, 1, 1});
    }

    @Test
    public void esDocsCatenateWords() {
        assertTokens(es("the wi-fi is enabled", Map.of("catenate_words", true)),
            new String[] {"the", "wifi", "wi", "fi", "is", "enabled"},
            new int[] {0, 4, 4, 7, 10, 13}, new int[] {3, 9, 6, 9, 12, 20},
            new int[] {1, 1, 0, 1, 1, 1}, new int[] {1, 2, 1, 1, 1, 1});
    }

    @Test
    public void esDocsCatenateNumbersAndExamples() {
        assertTokens(es("500-42", Map.of("catenate_numbers", "true")),
            new String[] {"50042", "500", "42"}, new int[] {0, 0, 4}, new int[] {6, 3, 6}, new int[] {1, 0, 1}, new int[] {2, 1, 1});
        assertTerms(es("PowerShot", Map.of()), "Power", "Shot");
        assertTerms(es("SD500", Map.of()), "SD", "500");
        assertTerms(es("SD500", Map.of("split_on_numerics", false)), "SD500");
        assertTerms(es("PowerShot", Map.of("split_on_case_change", "false")), "PowerShot");
        assertTerms(es("O'Neil's", Map.of()), "O", "Neil");
        assertTerms(es("O'Neil's", Map.of("stem_english_possessive", false)), "O", "Neil", "s");
        assertTerms(es("wi-fi-4000", Map.of("catenate_all", true)), "wifi4000", "wi", "fi", "4000");
        assertTokens(es("500-42", Map.of("preserve_original", true)),
            new String[] {"500-42", "500", "42"}, null, null, new int[] {1, 0, 1}, new int[] {2, 1, 1});
        assertTokens(es("wi-fi", Map.of("adjust_offsets", false, "catenate_words", true)),
            new String[] {"wifi", "wi", "fi"}, new int[] {0, 0, 0}, new int[] {5, 5, 5}, new int[] {1, 0, 1}, new int[] {2, 1, 1});
    }

    @Test
    public void reuseAfterReset() {
        TestWhitespaceTokenizer tok = new TestWhitespaceTokenizer("foo-bar baz");
        WordDelimiterGraphFilter f = new WordDelimiterGraphFilter(tok, true, TABLE, OFFSET_FLAGS, null);
        List<Token> first = WdAssert.collect(f);
        tok.setInput("foo-bar baz");
        List<Token> second = WdAssert.collect(f);
        assertEquals(first.toString(), second.toString());
        assertEquals(4, second.size());
    }
}
