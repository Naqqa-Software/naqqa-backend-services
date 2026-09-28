package com.naqqa.elasticsearch.analysis.lang.b;

import com.naqqa.elasticsearch.analysis.Analyzer;
import com.naqqa.elasticsearch.test.Assert;
import com.naqqa.elasticsearch.test.Test;

import java.util.List;

public final class CzechStemmerTest {

    private String stem(String word) {
        StringBuilder sb = new StringBuilder(word);
        new CzechStemmer().stem(sb);
        return sb.toString();
    }

    @Test
    public void stemsCommonVocabulary() {
        Assert.assertEquals("rychlík", stem("rychlík"));
        Assert.assertEquals("rychl", stem("rychlá"));
        Assert.assertEquals("rychl", stem("rychlé"));
        Assert.assertEquals("hněd", stem("hnědí"));
        Assert.assertEquals("hněd", stem("hnědá"));
        Assert.assertEquals("lišk", stem("liška"));
        Assert.assertEquals("lišk", stem("lišky"));
        Assert.assertEquals("skák", stem("skáčou"));
        Assert.assertEquals("skák", stem("skákat"));
        Assert.assertEquals("přs", stem("přes"));
        Assert.assertEquals("leniv", stem("lenivého"));
        Assert.assertEquals("leniv", stem("leniví"));
        Assert.assertEquals("ps", stem("pes"));
        Assert.assertEquals("psa", stem("psa"));
        Assert.assertEquals("psi", stem("psi"));
        Assert.assertEquals("byl", stem("byla"));
        Assert.assertEquals("byl", stem("byl"));
        Assert.assertEquals("velk", stem("velká"));
        Assert.assertEquals("velk", stem("velké"));
        Assert.assertEquals("radost", stem("radost"));
        Assert.assertEquals("radost", stem("radosti"));
        Assert.assertEquals("dom", stem("domu"));
        Assert.assertEquals("dom", stem("domů"));
        Assert.assertEquals("měst", stem("městech"));
    }

    @Test
    public void analyzerRemovesStopwordsAndStemsSentence() {
        Analyzer analyzer = CzechAnalyzerFactory.create(java.util.Map.of());
        List<String> terms = analyzer.analyze(null, "Rychlí hnědí lišky skáčou přes lenivého psa a byla to velká radost");
        Assert.assertFalse(terms.contains("a"));
        Assert.assertFalse(terms.contains("to"));
        Assert.assertFalse(terms.contains("přes"));
        Assert.assertTrue(terms.contains("lišk"));
        Assert.assertTrue(terms.contains("leniv"));
        Assert.assertTrue(terms.contains("velk"));
        Assert.assertTrue(terms.contains("radost"));
    }
}
