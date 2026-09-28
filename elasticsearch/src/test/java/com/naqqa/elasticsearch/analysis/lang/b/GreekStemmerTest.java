package com.naqqa.elasticsearch.analysis.lang.b;

import com.naqqa.elasticsearch.analysis.Analyzer;
import com.naqqa.elasticsearch.test.Assert;
import com.naqqa.elasticsearch.test.Test;

import java.util.List;

public final class GreekStemmerTest {

    private String stem(String word) {
        StringBuilder sb = new StringBuilder(word);
        new GreekStemmer().stem(sb);
        return sb.toString();
    }

    @Test
    public void stemsCommonVocabulary() {
        Assert.assertEquals("ανθρωπ", stem("ανθρωποι"));
        Assert.assertEquals("ανθρωπος", stem("ανθρωπος"));
        Assert.assertEquals("καλος", stem("καλος"));
        Assert.assertEquals("καλ", stem("καλη"));
        Assert.assertEquals("καλ", stem("καλο"));
        Assert.assertEquals("γραφ", stem("γραφω"));
        Assert.assertEquals("γραφεις", stem("γραφεις"));
        Assert.assertEquals("αγαπ", stem("αγαπη"));
        Assert.assertEquals("αγαπης", stem("αγαπης"));
        Assert.assertEquals("δουλει", stem("δουλεια"));
        Assert.assertEquals("δουλειες", stem("δουλειες"));
        Assert.assertEquals("παιδ", stem("παιδι"));
        Assert.assertEquals("παιδ", stem("παιδια"));
        Assert.assertEquals("σπιτ", stem("σπιτι"));
        Assert.assertEquals("σπιτ", stem("σπιτια"));
        Assert.assertEquals("φωτος", stem("φωτος"));
        Assert.assertEquals("φω", stem("φωτα"));
        Assert.assertEquals("βιβλι", stem("βιβλιο"));
        Assert.assertEquals("βιβλ", stem("βιβλια"));
        Assert.assertEquals("ελληνικος", stem("ελληνικος"));
    }

    @Test
    public void analyzerRemovesStopwordsNormalizesAccentsAndStems() {
        Analyzer analyzer = GreekAnalyzerFactory.create(java.util.Map.of());
        List<String> terms = analyzer.analyze(null, "Οι άνθρωποι είναι καλοί και γράφουν όμορφα ποιήματα");
        Assert.assertFalse(terms.contains("οι"));
        Assert.assertFalse(terms.contains("και"));
        Assert.assertFalse(terms.contains("ειναι"));
        Assert.assertTrue(terms.contains("ανθρωπ"));
        Assert.assertTrue(terms.contains("καλ"));
        Assert.assertTrue(terms.contains("γραφ"));
        Assert.assertTrue(terms.contains("ομορφ"));
        Assert.assertTrue(terms.contains("ποιημα"));
    }
}
