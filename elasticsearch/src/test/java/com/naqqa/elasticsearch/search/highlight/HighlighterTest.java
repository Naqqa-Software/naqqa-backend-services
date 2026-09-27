package com.naqqa.elasticsearch.search.highlight;

import com.naqqa.elasticsearch.analysis.Analyzer;
import com.naqqa.elasticsearch.analysis.registry.BuiltinAnalyzers;
import com.naqqa.elasticsearch.codec.termvectors.TermVectorTerm;
import com.naqqa.elasticsearch.test.Assert;
import com.naqqa.elasticsearch.test.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

public final class HighlighterTest {

    private Analyzer standard() {
        return BuiltinAnalyzers.get("standard");
    }

    @Test
    public void plainHighlighterReanalyzesAndWrapsMatches() {
        String text = "The quick brown fox jumps over the lazy dog.";
        SimpleHitContext hit = new SimpleHitContext().source("body", text).defaultAnalyzer(standard());
        HighlightRequest req = new HighlightRequest("body").addTerm("fox").addTerm("dog")
            .numberOfFragments(1).fragmentSize(100);
        List<String> result = new PlainHighlighter().highlight(req, hit);
        Assert.assertEquals(1, result.size());
        Assert.assertTrue(result.get(0).contains("<em>fox</em>"), result.get(0));
        Assert.assertTrue(result.get(0).contains("<em>dog</em>"), result.get(0));
    }

    @Test
    public void unifiedHighlighterFallsBackToAnalysisWithoutTermVectors() {
        String text = "The quick fox jumps over new fences. The dog and fox and dog play in the sunny yard together each day.";
        SimpleHitContext hit = new SimpleHitContext().source("body", text).defaultAnalyzer(standard());
        HighlightRequest req = new HighlightRequest("body").addTerm("fox").addTerm("dog")
            .numberOfFragments(1).fragmentSize(45);
        List<String> result = new UnifiedHighlighter().highlight(req, hit);
        Assert.assertEquals(1, result.size());
        String fragment = result.get(0);
        int emCount = countOccurrences(fragment, "<em>");
        Assert.assertEquals(3, emCount, fragment);
        Assert.assertTrue(fragment.contains("dog"), fragment);
        Assert.assertTrue(fragment.contains("fox"), fragment);
    }

    @Test
    public void unifiedHighlighterUsesTermVectorOffsetsWhenAvailable() {
        String text = "alpha beta gamma delta";
        byte[] betaTerm = "beta".getBytes(StandardCharsets.UTF_8);
        int start = text.indexOf("beta");
        int end = start + "beta".length();
        List<TermVectorTerm> tvs = List.of(new TermVectorTerm(betaTerm, 1, new int[]{1}, new int[]{start}, new int[]{end}));
        SimpleHitContext hit = new SimpleHitContext().source("body", text).termVectors("body", tvs);
        HighlightRequest req = new HighlightRequest("body").addTerm("beta").numberOfFragments(1).fragmentSize(100);
        List<String> result = new UnifiedHighlighter().highlight(req, hit);
        Assert.assertEquals(1, result.size());
        Assert.assertTrue(result.get(0).contains("<em>beta</em>"), result.get(0));
    }

    @Test
    public void fastVectorHighlighterThrowsWithoutTermVectors() {
        SimpleHitContext hit = new SimpleHitContext().source("body", "no vectors here").defaultAnalyzer(standard());
        HighlightRequest req = new HighlightRequest("body").addTerm("vectors");
        Assert.assertThrows(IllegalStateException.class, () -> new FastVectorHighlighter().highlight(req, hit));
    }

    @Test
    public void fastVectorHighlighterUsesWeightedTermVectors() {
        String text = "search search index search document";
        byte[] term = "search".getBytes(StandardCharsets.UTF_8);
        int[] starts = {0, 7, 21};
        int[] ends = {6, 13, 27};
        List<TermVectorTerm> tvs = List.of(new TermVectorTerm(term, 3, new int[]{0, 1, 3}, starts, ends));
        SimpleHitContext hit = new SimpleHitContext().source("body", text).termVectors("body", tvs);
        HighlightRequest req = new HighlightRequest("body").addTerm("search").numberOfFragments(1).fragmentSize(100);
        List<String> result = new FastVectorHighlighter().highlight(req, hit);
        Assert.assertEquals(1, result.size());
        Assert.assertEquals(3, countOccurrences(result.get(0), "<em>"));
    }

    @Test
    public void overlappingSpansMergeWithoutDoubleTagging() {
        String text = "I love New York City in the fall.";
        SimpleHitContext hit = new SimpleHitContext().source("body", text).defaultAnalyzer(standard());
        HighlightRequest req = new HighlightRequest("body").addTerm("york").addPhrase("New York")
            .numberOfFragments(1).fragmentSize(100);
        List<String> result = new PlainHighlighter().highlight(req, hit);
        Assert.assertEquals(1, result.size());
        String fragment = result.get(0);
        Assert.assertEquals(1, countOccurrences(fragment, "<em>"), fragment);
        Assert.assertEquals(1, countOccurrences(fragment, "</em>"), fragment);
        Assert.assertTrue(fragment.contains("<em>New York</em>"), fragment);
    }

    @Test
    public void noMatchSizeFallbackReturnsLeadingSnippet() {
        String text = "Nothing relevant appears in this sentence at all.";
        SimpleHitContext hit = new SimpleHitContext().source("body", text).defaultAnalyzer(standard());
        HighlightRequest req = new HighlightRequest("body").addTerm("zzz").noMatchSize(10);
        List<String> result = new PlainHighlighter().highlight(req, hit);
        Assert.assertEquals(1, result.size());
        Assert.assertTrue(text.startsWith(result.get(0)), result.get(0));
        Assert.assertFalse(result.get(0).contains("<em>"));
    }

    @Test
    public void wordBoundaryScannerNeverCutsInsideAWord() {
        String text = "The wonderfully quick brown foxes jumped swiftly over lazily sleeping dogs";
        for (int pos = 0; pos <= text.length(); pos++) {
            int preceding = BoundaryScanner.WORD.precedingBoundary(text, pos);
            int following = BoundaryScanner.WORD.followingBoundary(text, pos);
            assertNotInsideWord(text, preceding);
            assertNotInsideWord(text, following);
        }
    }

    private void assertNotInsideWord(String text, int idx) {
        if (idx <= 0 || idx >= text.length()) {
            return;
        }
        boolean leftLetter = Character.isLetter(text.charAt(idx - 1));
        boolean rightLetter = Character.isLetter(text.charAt(idx));
        if (leftLetter && rightLetter) {
            Assert.fail("boundary " + idx + " falls inside a word in [" + text + "]");
        }
    }

    private int countOccurrences(String haystack, String needle) {
        int count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) >= 0) {
            count++;
            idx += needle.length();
        }
        return count;
    }
}
