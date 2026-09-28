package com.naqqa.elasticsearch.analysis.lang.b;

import com.naqqa.elasticsearch.analysis.Analyzer;
import com.naqqa.elasticsearch.test.Assert;
import com.naqqa.elasticsearch.test.Test;

import java.util.List;

public final class EstonianStemmerTest {

    private String stem(String word) {
        StringBuilder sb = new StringBuilder(word);
        new EstonianStemmer().stem(sb);
        return sb.toString();
    }

    @Test
    public void stemsCommonVocabulary() {
        Assert.assertEquals("kala", stem("kalad"));
        Assert.assertEquals("kala", stem("kala"));
        Assert.assertEquals("kala", stem("kalade"));
        Assert.assertEquals("kala", stem("kalasid"));
        Assert.assertEquals("raama", stem("raamat"));
        Assert.assertEquals("raama", stem("raamatute"));
        Assert.assertEquals("raama", stem("raamatud"));
        Assert.assertEquals("laula", stem("laulan"));
        Assert.assertEquals("laula", stem("laulavad"));
        Assert.assertEquals("lauli", stem("laulis"));
        Assert.assertEquals("õpetaja", stem("õpetaja"));
        Assert.assertEquals("õpetaja", stem("õpetajad"));
        Assert.assertEquals("maja", stem("maja"));
        Assert.assertEquals("maja", stem("majad"));
        Assert.assertEquals("majja", stem("majja"));
        Assert.assertEquals("kooli", stem("koolis"));
        Assert.assertEquals("kooli", stem("koolid"));
        Assert.assertEquals("inimene", stem("inimene"));
        Assert.assertEquals("inimese", stem("inimesed"));
        Assert.assertEquals("tegi", stem("tegema"));
    }

    @Test
    public void analyzerRemovesStopwordsAndStemsSentence() {
        Analyzer analyzer = EstonianAnalyzerFactory.create(java.util.Map.of());
        List<String> terms = analyzer.analyze(null, "Kalad ujuvad kiiresti ja laulan laule");
        Assert.assertFalse(terms.contains("ja"));
        Assert.assertTrue(terms.contains("kala"));
        Assert.assertTrue(terms.contains("laula"));
        Assert.assertTrue(terms.contains("laule"));
    }
}
