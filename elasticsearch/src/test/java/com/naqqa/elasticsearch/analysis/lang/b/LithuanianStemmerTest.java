package com.naqqa.elasticsearch.analysis.lang.b;

import com.naqqa.elasticsearch.analysis.Analyzer;
import com.naqqa.elasticsearch.test.Assert;
import com.naqqa.elasticsearch.test.Test;

import java.util.List;

public final class LithuanianStemmerTest {

    private String stem(String word) {
        StringBuilder sb = new StringBuilder(word);
        new LithuanianStemmer().stem(sb);
        return sb.toString();
    }

    @Test
    public void stemsCommonVocabulary() {
        Assert.assertEquals("vyr", stem("vyrai"));
        Assert.assertEquals("vyr", stem("vyras"));
        Assert.assertEquals("nam", stem("namas"));
        Assert.assertEquals("nam", stem("namai"));
        Assert.assertEquals("moter", stem("moteris"));
        Assert.assertEquals("moter", stem("moterys"));
        Assert.assertEquals("ger", stem("gera"));
        Assert.assertEquals("ger", stem("geras"));
        Assert.assertEquals("ger", stem("gerai"));
        Assert.assertEquals("dirb", stem("dirbti"));
        Assert.assertEquals("dirb", stem("dirba"));
        Assert.assertEquals("dirb", stem("dirbo"));
        Assert.assertEquals("miest", stem("miestas"));
        Assert.assertEquals("miest", stem("miestai"));
        Assert.assertEquals("knyg", stem("knyga"));
        Assert.assertEquals("knyg", stem("knygos"));
        Assert.assertEquals("vaik", stem("vaikas"));
        Assert.assertEquals("vaik", stem("vaikai"));
        Assert.assertEquals("dien", stem("diena"));
        Assert.assertEquals("dien", stem("dienos"));
    }

    @Test
    public void analyzerRemovesStopwordsAndStemsSentence() {
        Analyzer analyzer = LithuanianAnalyzerFactory.create(java.util.Map.of());
        List<String> terms = analyzer.analyze(null, "Vyrai ir moterys dirba greitai ir gerai");
        Assert.assertFalse(terms.contains("ir"));
        Assert.assertTrue(terms.contains("vyr"));
        Assert.assertTrue(terms.contains("moter"));
        Assert.assertTrue(terms.contains("dirb"));
        Assert.assertTrue(terms.contains("ger"));
    }
}
