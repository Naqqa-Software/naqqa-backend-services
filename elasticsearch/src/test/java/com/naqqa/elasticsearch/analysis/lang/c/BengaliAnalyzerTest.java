package com.naqqa.elasticsearch.analysis.lang.c;

import com.naqqa.elasticsearch.analysis.Analyzer;
import com.naqqa.elasticsearch.test.Assert;
import com.naqqa.elasticsearch.test.Test;

import java.util.List;
import java.util.Map;

public final class BengaliAnalyzerTest {

    private String stem(com.naqqa.elasticsearch.analysis.stem.Stemmer stemmer, String word) {
        StringBuilder sb = new StringBuilder(word);
        stemmer.stem(sb);
        return sb.toString();
    }

    @Test
    public void indicNormalizerDecomposesBengaliRra() {
        Assert.assertEquals("ড়", stem(new IndicNormalizer(), "ড়"));
    }

    @Test
    public void indicNormalizerDecomposesBengaliYya() {
        Assert.assertEquals("য়", stem(new IndicNormalizer(), "য়"));
    }

    @Test
    public void bengaliNormalizerFoldsKhandaTa() {
        Assert.assertEquals("ত", stem(new BengaliNormalizer(), "ৎ"));
    }

    @Test
    public void bengaliNormalizerFoldsAssameseRa() {
        Assert.assertEquals("র", stem(new BengaliNormalizer(), "ৰ"));
    }

    @Test
    public void bengaliNormalizerFoldsAssameseVa() {
        Assert.assertEquals("ব", stem(new BengaliNormalizer(), "ৱ"));
    }

    @Test
    public void bengaliNormalizerRemovesZeroWidthNonJoiner() {
        Assert.assertEquals("ক", stem(new BengaliNormalizer(), "ক‌"));
    }

    @Test
    public void bengaliStemmerStripsTiSuffix() {
        Assert.assertEquals("বই", stem(new BengaliStemmer(), "বইটি"));
    }

    @Test
    public void bengaliStemmerStripsGuloSuffix() {
        Assert.assertEquals("কলম", stem(new BengaliStemmer(), "কলমগুলো"));
    }

    @Test
    public void bengaliStemmerRespectsMinimumRemainingLength() {
        Assert.assertEquals("টি", stem(new BengaliStemmer(), "টি"));
    }

    @Test
    public void bengaliAnalyzerRemovesStopwordsAndStemsFullSentence() {
        Analyzer analyzer = BengaliAnalyzerFactory.create(Map.of());
        List<String> terms = analyzer.analyze(null, "বইটি ও না");
        Assert.assertEquals(List.of("বই"), terms);
    }
}
