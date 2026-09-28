package com.naqqa.elasticsearch.analysis.lang.a;

import com.naqqa.elasticsearch.analysis.Analyzer;
import com.naqqa.elasticsearch.analysis.stem.Stemmer;
import com.naqqa.elasticsearch.test.Assert;
import com.naqqa.elasticsearch.test.Test;

import java.util.List;
import java.util.Map;

public final class IndonesianAnalyzerFactoryTest {

    private static final String[][] VOCABULARY = {
        {"bukukah", "buku"},
        {"adalah", "ada"},
        {"bukupun", "buku"},
        {"bukuku", "buku"},
        {"bukumu", "buku"},
        {"bukunya", "buku"},
        {"mengukur", "ukur"},
        {"menyapu", "sapu"},
        {"menduga", "duga"},
        {"membaca", "baca"},
        {"pengukur", "ukur"},
        {"diukur", "ukur"},
        {"tersapu", "sapu"},
        {"kekasih", "kasih"},
        {"berlari", "lari"},
        {"belajar", "ajar"},
        {"bekerja", "kerja"},
        {"pelajar", "ajar"},
        {"makanan", "makan"},
        {"perubahan", "ubah"},
        {"kepolisian", "polisi"},
        {"bersenjata", "senjata"},
    };

    @Test
    public void stemsOfficialVocabularySamples() {
        Stemmer stemmer = IndonesianAnalyzerFactory.stemmer();
        for (String[] pair : VOCABULARY) {
            Assert.assertEquals(pair[1], stemmer.stem(pair[0]), "stem(" + pair[0] + ")");
        }
    }

    @Test
    public void inflectionalOnlyStemmerDoesNotApplyDerivationalRules() {
        Stemmer stemmer = IndonesianAnalyzerFactory.stemmer(false);
        Assert.assertEquals("buku", stemmer.stem("bukunya"));
        Assert.assertEquals("dibukukan", stemmer.stem("dibukukannya"));
    }

    @Test
    public void analyzerRemovesStopwordsAndStemsSentence() {
        Analyzer analyzer = IndonesianAnalyzerFactory.create(Map.of());
        List<String> terms = analyzer.analyze(null, "Dia sedang membaca buku di perpustakaan dan mereka akan makanan bersama");
        Assert.assertFalse(terms.contains("dia"));
        Assert.assertFalse(terms.contains("di"));
        Assert.assertFalse(terms.contains("dan"));
        Assert.assertTrue(terms.contains("baca"));
        Assert.assertTrue(terms.contains("buku"));
        Assert.assertTrue(terms.contains("makan"));
    }
}
