package com.naqqa.elasticsearch.analysis.filter;

import com.naqqa.elasticsearch.analysis.Token;
import com.naqqa.elasticsearch.analysis.TokenStream;
import com.naqqa.elasticsearch.analysis.stem.Stemmer;
import com.naqqa.elasticsearch.analysis.stem.Stemmers;
import com.naqqa.elasticsearch.analysis.tokenizer.StandardTokenizer;
import com.naqqa.elasticsearch.analysis.tokenizer.WhitespaceTokenizer;
import com.naqqa.elasticsearch.test.Assert;
import com.naqqa.elasticsearch.test.Test;

import java.util.ArrayList;
import java.util.List;

public final class FiltersSmokeTest {

    private WhitespaceTokenizer tokenizer(String text) {
        WhitespaceTokenizer t = new WhitespaceTokenizer();
        t.setInput(text);
        return t;
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

    @Test
    public void shingleFilterProducesBigrams() {
        ShingleFilter f = new ShingleFilter(tokenizer("a b c"), 2, 2, true, false, " ", "_");
        Assert.assertEquals(List.of("a", "a b", "b", "b c", "c"), drain(f));
    }

    @Test
    public void cjkBigramFilterCombinesAdjacentHanChars() {
        StandardTokenizer tok = new StandardTokenizer();
        tok.setInput("一二三");
        CJKBigramFilter f = new CJKBigramFilter(tok, true, true, true, true, false);
        Assert.assertEquals(List.of("一二", "二三"), drain(f));
    }

    @Test
    public void stemmerFilterAppliesEnglishStemmer() {
        Stemmer stemmer = Stemmers.create("english");
        StemmerFilter f = new StemmerFilter(tokenizer("running jumps"), stemmer);
        List<String> terms = drain(f);
        Assert.assertEquals("running".length() > terms.get(0).length(), true);
    }

    @Test
    public void wordDelimiterGraphSplitsCamelCase() {
        com.naqqa.elasticsearch.analysis.filter.worddelimiter.WordDelimiterGraphFilter f =
            new com.naqqa.elasticsearch.analysis.filter.worddelimiter.WordDelimiterGraphFilter(
                tokenizer("PowerShot"),
                com.naqqa.elasticsearch.analysis.filter.worddelimiter.WordDelimiterGraphFilter.GENERATE_WORD_PARTS
                    | com.naqqa.elasticsearch.analysis.filter.worddelimiter.WordDelimiterGraphFilter.SPLIT_ON_CASE_CHANGE,
                null);
        Assert.assertEquals(List.of("Power", "Shot"), drain(f));
    }

    @Test
    public void asciiFoldingFilterFoldsAccents() {
        ASCIIFoldingFilter f = new ASCIIFoldingFilter(tokenizer("café"), false);
        Assert.assertEquals(List.of("cafe"), drain(f));
    }
}
