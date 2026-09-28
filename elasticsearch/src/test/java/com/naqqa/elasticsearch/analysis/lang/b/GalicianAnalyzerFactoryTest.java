package com.naqqa.elasticsearch.analysis.lang.b;

import com.naqqa.elasticsearch.analysis.Analyzer;
import com.naqqa.elasticsearch.analysis.stem.light.GalicianMinimalStemmer;
import com.naqqa.elasticsearch.test.Assert;
import com.naqqa.elasticsearch.test.Test;

import java.util.List;

public final class GalicianAnalyzerFactoryTest {

    private String stem(String word) {
        StringBuilder sb = new StringBuilder(word);
        GalicianAnalyzerFactory.stemmer().stem(sb);
        return sb.toString();
    }

    @Test
    public void factoryExposesTheGalicianMinimalStemmer() {
        Assert.assertTrue(GalicianAnalyzerFactory.stemmer() instanceof GalicianMinimalStemmer);
    }

    @Test
    public void stemsCommonVocabulary() {
        Assert.assertEquals("can", stem("cans"));
        Assert.assertEquals("can", stem("can"));
        Assert.assertEquals("gato", stem("gatos"));
        Assert.assertEquals("gato", stem("gato"));
        Assert.assertEquals("casa", stem("casas"));
        Assert.assertEquals("casa", stem("casa"));
        Assert.assertEquals("rapaz", stem("rapaces"));
        Assert.assertEquals("rapaz", stem("rapaz"));
        Assert.assertEquals("lun", stem("luns"));
        Assert.assertEquals("cail", stem("cais"));
        Assert.assertEquals("irman", stem("irmans"));
        Assert.assertEquals("irman", stem("irman"));
        Assert.assertEquals("monte", stem("montes"));
        Assert.assertEquals("monte", stem("monte"));
        Assert.assertEquals("xoves", stem("xoves"));
        Assert.assertEquals("martes", stem("martes"));
        Assert.assertEquals("pais", stem("paises"));
        Assert.assertEquals("pai", stem("pais"));
        Assert.assertEquals("nube", stem("nubes"));
        Assert.assertEquals("nube", stem("nube"));
    }

    @Test
    public void analyzerRemovesStopwordsAndStemsSentence() {
        Analyzer analyzer = GalicianAnalyzerFactory.create(java.util.Map.of());
        List<String> terms = analyzer.analyze(null, "Os cans rapidos correron polos montes e os gatos tamen correron");
        Assert.assertFalse(terms.contains("os"));
        Assert.assertFalse(terms.contains("e"));
        Assert.assertFalse(terms.contains("polos"));
        Assert.assertTrue(terms.contains("can"));
        Assert.assertTrue(terms.contains("gato"));
        Assert.assertTrue(terms.contains("monte"));
        Assert.assertTrue(terms.contains("correron"));
    }
}
