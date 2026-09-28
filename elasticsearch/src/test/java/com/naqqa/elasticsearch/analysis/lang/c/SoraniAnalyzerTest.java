package com.naqqa.elasticsearch.analysis.lang.c;

import com.naqqa.elasticsearch.analysis.Analyzer;
import com.naqqa.elasticsearch.test.Assert;
import com.naqqa.elasticsearch.test.Test;

import java.util.List;
import java.util.Map;

public final class SoraniAnalyzerTest {

    private String stem(com.naqqa.elasticsearch.analysis.stem.Stemmer stemmer, String word) {
        StringBuilder sb = new StringBuilder(word);
        stemmer.stem(sb);
        return sb.toString();
    }

    @Test
    public void soraniNormalizerFoldsYehToFarsiYeh() {
        Assert.assertEquals("ی", stem(new SoraniNormalizer(), "ي"));
    }

    @Test
    public void soraniNormalizerFoldsDotlessYehToFarsiYeh() {
        Assert.assertEquals("ی", stem(new SoraniNormalizer(), "ى"));
    }

    @Test
    public void soraniNormalizerFoldsKafToKeheh() {
        Assert.assertEquals("ک", stem(new SoraniNormalizer(), "ك"));
    }

    @Test
    public void soraniNormalizerFoldsTehMarbutaToAe() {
        Assert.assertEquals("ە", stem(new SoraniNormalizer(), "ة"));
    }

    @Test
    public void soraniNormalizerFoldsHehGoalToHeh() {
        Assert.assertEquals("ه", stem(new SoraniNormalizer(), "ہ"));
    }

    @Test
    public void soraniNormalizerFoldsHehZwnjToAe() {
        Assert.assertEquals("ە", stem(new SoraniNormalizer(), "ه‌"));
    }

    @Test
    public void soraniNormalizerRemovesStrayZeroWidthNonJoiner() {
        Assert.assertEquals("ان", stem(new SoraniNormalizer(), "ا‌ن"));
    }

    @Test
    public void soraniStemmerStripsAnSuffix() {
        Assert.assertEquals("کور", stem(new SoraniStemmer(), "کوران"));
    }

    @Test
    public void soraniStemmerStripsEkeSuffix() {
        Assert.assertEquals("کتێب", stem(new SoraniStemmer(), "کتێبەکە"));
    }

    @Test
    public void soraniStemmerRespectsMinimumRemainingLength() {
        Assert.assertEquals("ان", stem(new SoraniStemmer(), "ان"));
    }

    @Test
    public void soraniAnalyzerRemovesStopwordsAndStemsFullSentence() {
        Analyzer analyzer = SoraniAnalyzerFactory.create(Map.of());
        List<String> terms = analyzer.analyze(null, "کتاب و کوران");
        Assert.assertEquals(List.of("کتاب", "کور"), terms);
    }
}
