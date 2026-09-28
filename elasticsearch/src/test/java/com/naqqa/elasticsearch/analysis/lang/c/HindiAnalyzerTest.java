package com.naqqa.elasticsearch.analysis.lang.c;

import com.naqqa.elasticsearch.analysis.Analyzer;
import com.naqqa.elasticsearch.test.Assert;
import com.naqqa.elasticsearch.test.Test;

import java.util.List;
import java.util.Map;

public final class HindiAnalyzerTest {

    private String stem(com.naqqa.elasticsearch.analysis.stem.Stemmer stemmer, String word) {
        StringBuilder sb = new StringBuilder(word);
        stemmer.stem(sb);
        return sb.toString();
    }

    @Test
    public void indicNormalizerDecomposesQa() {
        Assert.assertEquals("क़", stem(new IndicNormalizer(), "क़"));
    }

    @Test
    public void indicNormalizerDecomposesYya() {
        Assert.assertEquals("य़", stem(new IndicNormalizer(), "य़"));
    }

    @Test
    public void indicNormalizerDecomposesNnna() {
        Assert.assertEquals("ऩ", stem(new IndicNormalizer(), "ऩ"));
    }

    @Test
    public void hindiNormalizerFoldsCandraE() {
        Assert.assertEquals("े", stem(new HindiNormalizer(), "ॅ"));
    }

    @Test
    public void hindiNormalizerFoldsShortE() {
        Assert.assertEquals("े", stem(new HindiNormalizer(), "ॆ"));
    }

    @Test
    public void hindiNormalizerFoldsCandraO() {
        Assert.assertEquals("ो", stem(new HindiNormalizer(), "ॉ"));
    }

    @Test
    public void hindiNormalizerFoldsCandraA() {
        Assert.assertEquals("अ", stem(new HindiNormalizer(), "ॲ"));
    }

    @Test
    public void hindiNormalizerRemovesNukta() {
        Assert.assertEquals("क", stem(new HindiNormalizer(), "क़"));
    }

    @Test
    public void hindiNormalizerRemovesZeroWidthJoiner() {
        Assert.assertEquals("क", stem(new HindiNormalizer(), "क‍"));
    }

    @Test
    public void hindiNormalizerTrimsTrailingVirama() {
        Assert.assertEquals("राम", stem(new HindiNormalizer(), "राम्"));
    }

    @Test
    public void hindiStemmerStripsTaSuffix() {
        Assert.assertEquals("कर", stem(new HindiStemmer(), "करता"));
    }

    @Test
    public void hindiStemmerStripsTiSuffix() {
        Assert.assertEquals("चल", stem(new HindiStemmer(), "चलती"));
    }

    @Test
    public void hindiStemmerRespectsMinimumRemainingLength() {
        Assert.assertEquals("का", stem(new HindiStemmer(), "का"));
    }

    @Test
    public void hindiAnalyzerRemovesStopwordsAndStemsFullSentence() {
        Analyzer analyzer = HindiAnalyzerFactory.create(Map.of());
        List<String> terms = analyzer.analyze(null, "राम और चलती तो");
        Assert.assertEquals(List.of("राम", "चल"), terms);
    }
}
