package com.naqqa.elasticsearch.analysis.tokenizer;

import com.naqqa.elasticsearch.analysis.Token;
import com.naqqa.elasticsearch.analysis.TokenStream;
import com.naqqa.elasticsearch.test.Assert;
import com.naqqa.elasticsearch.test.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public final class TokenizersSmokeTest {

    private List<String> drain(TokenStream ts) {
        List<String> out = new ArrayList<>();
        ts.reset();
        while (ts.incrementToken()) {
            out.add(ts.token().term());
        }
        ts.end();
        ts.close();
        return out;
    }

    @Test
    public void ngramTokenizerProducesAllGrams() {
        NGramTokenizer t = new NGramTokenizer(1, 2);
        t.setInput("ab");
        Assert.assertEquals(List.of("a", "ab", "b"), drain(t));
    }

    @Test
    public void edgeNgramTokenizerProducesPrefixes() {
        EdgeNGramTokenizer t = new EdgeNGramTokenizer(1, 3);
        t.setInput("abcd");
        Assert.assertEquals(List.of("a", "ab", "abc"), drain(t));
    }

    @Test
    public void pathHierarchyTokenizerBuildsPrefixes() {
        PathHierarchyTokenizer t = new PathHierarchyTokenizer('/', '/', 0, false, 1024);
        t.setInput("/one/two/three");
        Assert.assertEquals(List.of("/one", "/one/two", "/one/two/three"), drain(t));
    }

    @Test
    public void pathHierarchyTokenizerReverse() {
        PathHierarchyTokenizer t = new PathHierarchyTokenizer('/', '/', 0, true, 1024);
        t.setInput("/one/two/three");
        Assert.assertEquals(List.of("/three", "/two/three", "/one/two/three"), drain(t));
    }

    @Test
    public void uaxUrlEmailTokenizerRecognizesUrlAndEmail() {
        UaxUrlEmailTokenizer t = new UaxUrlEmailTokenizer();
        t.reset();
        t.setInput("visit http://example.com or email a@b.com today");
        List<String> types = new ArrayList<>();
        List<String> terms = new ArrayList<>();
        t.reset();
        while (t.incrementToken()) {
            Token tok = t.token();
            terms.add(tok.term());
            types.add(tok.type());
        }
        Assert.assertTrue(terms.contains("http://example.com"));
        Assert.assertTrue(terms.contains("a@b.com"));
        Assert.assertTrue(types.contains(UaxUrlEmailTokenizer.URL));
        Assert.assertTrue(types.contains(UaxUrlEmailTokenizer.EMAIL));
    }

    @Test
    public void patternTokenizerSplitMode() {
        PatternTokenizer t = new PatternTokenizer(Pattern.compile(","), -1);
        t.setInput("a,b,,c");
        Assert.assertEquals(List.of("a", "b", "", "c"), drain(t));
    }

    @Test
    public void simplePatternSplitTokenizerSplitsOnDelimiter() {
        SimplePatternSplitTokenizer t = new SimplePatternSplitTokenizer(Pattern.compile("_"));
        t.setInput("foo_bar_baz");
        Assert.assertEquals(List.of("foo", "bar", "baz"), drain(t));
    }
}
