package com.naqqa.elasticsearch.analysis.lang.a;

import com.naqqa.elasticsearch.analysis.Analyzer;
import com.naqqa.elasticsearch.analysis.stem.Stemmer;
import com.naqqa.elasticsearch.test.Assert;
import com.naqqa.elasticsearch.test.Test;

import java.util.List;
import java.util.Map;

public final class HungarianAnalyzerFactoryTest {

    private static final String[][] VOCABULARY = {
        {"milyenek", "mily"},
        {"bocsátotta", "bocsátott"},
        {"leküzdendő", "leküzdendő"},
        {"malaca", "malac"},
        {"kívánatosak", "kívánatos"},
        {"borongós", "borongós"},
        {"kapitánytól", "kapitány"},
        {"minősüljön", "minősülj"},
        {"kezdete", "kezdet"},
        {"alakjának", "al"},
        {"korukba", "kor"},
        {"képem", "kép"},
        {"magyarázgatni", "magyarázgatn"},
        {"magamban", "mag"},
        {"birság", "birság"},
        {"bólogattak", "bólogatt"},
        {"keresőfunkciói", "keresőfunkció"},
        {"kecskekörmeit", "kecskekörm"},
        {"megkönnyebbült", "megkönnyebbül"},
    };

    @Test
    public void stemsOfficialVocabularySamples() {
        Stemmer stemmer = HungarianAnalyzerFactory.stemmer();
        for (String[] pair : VOCABULARY) {
            Assert.assertEquals(pair[1], stemmer.stem(pair[0]), "stem(" + pair[0] + ")");
        }
    }

    @Test
    public void analyzerRemovesStopwordsAndStemsSentence() {
        Analyzer analyzer = HungarianAnalyzerFactory.create(Map.of());
        List<String> terms = analyzer.analyze(null, "A gyerekek az iskolában tanulnak és a házi feladatot is megcsinálják");
        Assert.assertFalse(terms.contains("a"));
        Assert.assertFalse(terms.contains("az"));
        Assert.assertFalse(terms.contains("és"));
        Assert.assertTrue(terms.contains("gyerek"));
    }
}
