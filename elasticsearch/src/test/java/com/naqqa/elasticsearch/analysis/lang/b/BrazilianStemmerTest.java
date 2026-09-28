package com.naqqa.elasticsearch.analysis.lang.b;

import com.naqqa.elasticsearch.analysis.Analyzer;
import com.naqqa.elasticsearch.test.Assert;
import com.naqqa.elasticsearch.test.Test;

import java.util.List;

public final class BrazilianStemmerTest {

    private String stem(String word) {
        StringBuilder sb = new StringBuilder(word);
        new BrazilianStemmer().stem(sb);
        return sb.toString();
    }

    @Test
    public void stemsCommonVocabulary() {
        Assert.assertEquals("gat", stem("gato"));
        Assert.assertEquals("gat", stem("gatos"));
        Assert.assertEquals("rapid", stem("rapidamente"));
        Assert.assertEquals("nacional", stem("nacional"));
        Assert.assertEquals("nacional", stem("nacionalidade"));
        Assert.assertEquals("corredor", stem("corredor"));
        Assert.assertEquals("corredor", stem("corredores"));
        Assert.assertEquals("menin", stem("menina"));
        Assert.assertEquals("menin", stem("menino"));
        Assert.assertEquals("cas", stem("casas"));
        Assert.assertEquals("organiz", stem("organizacao"));
        Assert.assertEquals("organiz", stem("organizacoes"));
        Assert.assertEquals("feliz", stem("feliz"));
        Assert.assertEquals("feliz", stem("felizes"));
        Assert.assertEquals("bonit", stem("bonita"));
        Assert.assertEquals("bonit", stem("bonito"));
    }

    @Test
    public void analyzerRemovesStopwordsAndStemsSentence() {
        Analyzer analyzer = BrazilianAnalyzerFactory.create(java.util.Map.of());
        List<String> terms = analyzer.analyze(null, "As raposas rapidas correram pelas florestas e os caes felizes correram tambem");
        Assert.assertFalse(terms.contains("as"));
        Assert.assertFalse(terms.contains("e"));
        Assert.assertFalse(terms.contains("os"));
        Assert.assertFalse(terms.contains("tambem"));
        Assert.assertTrue(terms.contains("rapos"));
        Assert.assertTrue(terms.contains("corr"));
        Assert.assertTrue(terms.contains("florest"));
        Assert.assertTrue(terms.contains("feliz"));
    }
}
