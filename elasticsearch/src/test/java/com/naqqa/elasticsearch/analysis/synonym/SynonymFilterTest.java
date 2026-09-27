package com.naqqa.elasticsearch.analysis.synonym;

import com.naqqa.elasticsearch.analysis.Token;
import com.naqqa.elasticsearch.analysis.TokenStream;
import com.naqqa.elasticsearch.analysis.tokenizer.WhitespaceTokenizer;
import com.naqqa.elasticsearch.test.Assert;
import com.naqqa.elasticsearch.test.Test;

import java.util.ArrayList;
import java.util.List;

public final class SynonymFilterTest {

    private WhitespaceTokenizer tokenizerFor(String text) {
        WhitespaceTokenizer t = new WhitespaceTokenizer();
        t.setInput(text);
        return t;
    }

    @Test
    public void expandsSingleWordSynonymGroup() {
        SynonymMap map = SynonymMap.build(List.of("i-pod, ipod, i pod"), true, false, false);
        WhitespaceTokenizer tok = tokenizerFor("the i-pod is nice");
        SynonymFilter filter = new SynonymFilter(tok, map);
        List<String> terms = drain(filter);
        Assert.assertTrue(terms.contains("i-pod"));
        Assert.assertTrue(terms.contains("ipod"));
        Assert.assertTrue(terms.contains("i"));
        Assert.assertTrue(terms.contains("pod"));
    }

    @Test
    public void graphFilterSetsPositionLengthForMultiWordInput() {
        SynonymMap map = SynonymMap.build(List.of("personal computer => pc"), false, false, false);
        WhitespaceTokenizer tok = tokenizerFor("my personal computer works");
        SynonymGraphFilter filter = new SynonymGraphFilter(tok, map);
        filter.reset();
        int foundPositionLength2 = 0;
        while (filter.incrementToken()) {
            Token t = filter.token();
            if (t.term().equals("pc")) {
                Assert.assertEquals(2, t.positionLength());
                foundPositionLength2++;
            }
        }
        filter.end();
        filter.close();
        Assert.assertEquals(1, foundPositionLength2);
    }

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
}
