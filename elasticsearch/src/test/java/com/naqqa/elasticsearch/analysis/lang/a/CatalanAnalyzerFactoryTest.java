package com.naqqa.elasticsearch.analysis.lang.a;

import com.naqqa.elasticsearch.analysis.Analyzer;
import com.naqqa.elasticsearch.analysis.stem.Stemmer;
import com.naqqa.elasticsearch.test.Assert;
import com.naqqa.elasticsearch.test.Test;

import java.util.List;
import java.util.Map;

public final class CatalanAnalyzerFactoryTest {

    private static final String[][] VOCABULARY = {
        {"superant", "super"},
        {"manejat", "manej"},
        {"animant", "anim"},
        {"esfèrica", "esferic"},
        {"restringida", "restring"},
        {"desapareixent", "desapareix"},
        {"orfeons", "orfeon"},
        {"durava", "dur"},
        {"descontrolada", "descontrol"},
        {"hepàtic", "hepatic"},
        {"area", "are"},
        {"acusats", "acu"},
        {"abanderat", "abander"},
        {"lúcid", "luc"},
        {"cobricel", "cobricel"},
        {"casolans", "casolan"},
        {"rient", "rient"},
        {"heterodox", "heterodox"},
        {"poder", "pod"},
    };

    @Test
    public void stemsOfficialVocabularySamples() {
        Stemmer stemmer = CatalanAnalyzerFactory.stemmer();
        for (String[] pair : VOCABULARY) {
            Assert.assertEquals(pair[1], stemmer.stem(pair[0]), "stem(" + pair[0] + ")");
        }
    }

    @Test
    public void analyzerAppliesElisionRemovesStopwordsAndStems() {
        Analyzer analyzer = CatalanAnalyzerFactory.create(Map.of());
        List<String> terms = analyzer.analyze(null, "L'home i la dona parlaven de l'ordinador i de l'aigua d'un got");
        Assert.assertFalse(terms.contains("l'home"));
        Assert.assertFalse(terms.contains("i"));
        Assert.assertFalse(terms.contains("la"));
        Assert.assertFalse(terms.contains("de"));
        Assert.assertEquals(List.of("hom", "don", "parl", "ordin", "aigu", "got"), terms);
    }
}
