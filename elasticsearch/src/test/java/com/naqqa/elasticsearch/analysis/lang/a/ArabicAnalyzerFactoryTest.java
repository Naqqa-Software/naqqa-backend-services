package com.naqqa.elasticsearch.analysis.lang.a;

import com.naqqa.elasticsearch.analysis.Analyzer;
import com.naqqa.elasticsearch.analysis.stem.Stemmer;
import com.naqqa.elasticsearch.test.Assert;
import com.naqqa.elasticsearch.test.Test;

import java.util.List;
import java.util.Map;

public final class ArabicAnalyzerFactoryTest {

    private static final String[][] NORMALIZATION = {
        {"آجن", "اجن"},
        {"أحمد", "احمد"},
        {"إعاذ", "اعاذ"},
        {"بنى", "بني"},
        {"فاطمة", "فاطمه"},
        {"روبرـــت", "روبرت"},
        {"مَبنا", "مبنا"},
        {"علِي", "علي"},
        {"ولدًا", "ولدا"},
        {"ولدٍ", "ولد"},
        {"نلْسون", "نلسون"},
        {"هتمِّي", "هتمي"},
    };

    private static final String[][] STEMMING = {
        {"الحسن", "حسن"},
        {"والحسن", "حسن"},
        {"بالحسن", "حسن"},
        {"كالحسن", "حسن"},
        {"فالحسن", "حسن"},
        {"للاخر", "اخر"},
        {"وحسن", "حسن"},
        {"زوجها", "زوج"},
        {"ساهدان", "ساهد"},
        {"ساهدات", "ساهد"},
        {"ساهدون", "ساهد"},
        {"ساهدين", "ساهد"},
        {"ساهديه", "ساهد"},
        {"ساهدية", "ساهد"},
        {"ساهده", "ساهد"},
        {"ساهدة", "ساهد"},
        {"ساهدي", "ساهد"},
        {"الو", "الو"},
    };

    @Test
    public void normalizesOfficialVocabularySamples() {
        Stemmer normalizer = ArabicAnalyzerFactory.normalizer();
        for (String[] pair : NORMALIZATION) {
            Assert.assertEquals(pair[1], normalizer.stem(pair[0]), "normalize(" + pair[0] + ")");
        }
    }

    @Test
    public void stemsOfficialVocabularySamples() {
        Stemmer stemmer = ArabicAnalyzerFactory.stemmer();
        for (String[] pair : STEMMING) {
            Assert.assertEquals(pair[1], stemmer.stem(pair[0]), "stem(" + pair[0] + ")");
        }
    }

    @Test
    public void analyzerNormalizesRemovesStopwordsAndStemsSentence() {
        Analyzer analyzer = ArabicAnalyzerFactory.create(Map.of());
        List<String> terms = analyzer.analyze(null,
            "الطلاب يذهبون الى "
                + "المدرسة والمعلمون");
        Assert.assertFalse(terms.contains("الى"));
        Assert.assertTrue(terms.contains("طلاب"));
        Assert.assertTrue(terms.contains("مدرس"));
    }
}
