package com.naqqa.elasticsearch.analysis.lang.a;

import com.naqqa.elasticsearch.analysis.Analyzer;
import com.naqqa.elasticsearch.analysis.stem.Stemmer;
import com.naqqa.elasticsearch.test.Assert;
import com.naqqa.elasticsearch.test.Test;

import java.util.List;
import java.util.Map;

public final class IrishAnalyzerFactoryTest {

    private static final String[][] VOCABULARY = {
        {"iobraíos", "iobraíos"},
        {"dhearfa", "dearfa"},
        {"bproinn", "proinn"},
        {"pholaitéine", "polaitéine"},
        {"dtogróinn", "togróinn"},
        {"fhorbraíonn", "forbraíonn"},
        {"fháilitiú", "fáilitiú"},
        {"chuimilte", "cuimilte"},
        {"mbruithfaí", "bruithfaí"},
        {"phátrúin", "pátrúin"},
        {"rómúineadh", "rómúin"},
        {"hoilithreacht", "hoilithr"},
        {"slaig", "slaig"},
        {"chroite", "croite"},
        {"mhearbhia", "mearbhia"},
        {"múinteoireacht", "múinteoir"},
        {"frathachaí", "frathachaí"},
        {"glaoifidh", "glaoi"},
    };

    @Test
    public void stemsOfficialVocabularySamples() {
        Stemmer stemmer = IrishAnalyzerFactory.stemmer();
        for (String[] pair : VOCABULARY) {
            Assert.assertEquals(pair[1], stemmer.stem(pair[0]), "stem(" + pair[0] + ")");
        }
    }

    @Test
    public void analyzerAppliesElisionHyphenationAndStemsSentence() {
        Analyzer analyzer = IrishAnalyzerFactory.create(Map.of());
        List<String> terms = analyzer.analyze(null, "Chuaigh an t-athair go dti an siopa agus cheannaigh se bainne");
        Assert.assertFalse(terms.contains("an"));
        Assert.assertFalse(terms.contains("t"));
        Assert.assertTrue(terms.contains("athair"));
        Assert.assertTrue(terms.contains("cuaigh"));
    }
}
