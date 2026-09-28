package com.naqqa.elasticsearch.analysis.lang.b;

import com.naqqa.elasticsearch.analysis.Analyzer;
import com.naqqa.elasticsearch.test.Assert;
import com.naqqa.elasticsearch.test.Test;

import java.util.List;

public final class LatvianStemmerTest {

    private String stem(String word) {
        StringBuilder sb = new StringBuilder(word);
        new LatvianStemmer().stem(sb);
        return sb.toString();
    }

    @Test
    public void stemsCommonVocabulary() {
        Assert.assertEquals("ātr", stem("ātrs"));
        Assert.assertEquals("ātr", stem("ātra"));
        Assert.assertEquals("ātr", stem("ātrais"));
        Assert.assertEquals("brun", stem("bruns"));
        Assert.assertEquals("brun", stem("bruna"));
        Assert.assertEquals("laps", stem("lapsa"));
        Assert.assertEquals("laps", stem("lapsas"));
        Assert.assertEquals("pārskrien", stem("pārskrien"));
        Assert.assertEquals("slink", stem("slinkais"));
        Assert.assertEquals("slink", stem("slinkajam"));
        Assert.assertEquals("sun", stem("suns"));
        Assert.assertEquals("sun", stem("sunim"));
        Assert.assertEquals("sun", stem("suņa"));
        Assert.assertEquals("liel", stem("lielais"));
        Assert.assertEquals("liel", stem("liels"));
        Assert.assertEquals("liel", stem("liela"));
        Assert.assertEquals("priek", stem("prieks"));
        Assert.assertEquals("priek", stem("prieku"));
        Assert.assertEquals("māj", stem("māja"));
        Assert.assertEquals("māj", stem("mājas"));
        Assert.assertEquals("kok", stem("koks"));
        Assert.assertEquals("kok", stem("koki"));
        Assert.assertEquals("zem", stem("zeme"));
        Assert.assertEquals("zem", stem("zemes"));
    }

    @Test
    public void analyzerRemovesStopwordsAndStemsSentence() {
        Analyzer analyzer = LatvianAnalyzerFactory.create(java.util.Map.of());
        List<String> terms = analyzer.analyze(null, "Ātrā bruna lapsa pārskrien pāri slinkajam sunim un tas bija liels prieks");
        Assert.assertFalse(terms.contains("un"));
        Assert.assertTrue(terms.contains("brun"));
        Assert.assertTrue(terms.contains("laps"));
        Assert.assertTrue(terms.contains("slink"));
        Assert.assertTrue(terms.contains("sun"));
        Assert.assertTrue(terms.contains("liel"));
        Assert.assertTrue(terms.contains("priek"));
    }
}
