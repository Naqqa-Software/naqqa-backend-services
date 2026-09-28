package com.naqqa.elasticsearch.analysis.lang.c;

import com.naqqa.elasticsearch.analysis.Analyzer;
import com.naqqa.elasticsearch.test.Assert;
import com.naqqa.elasticsearch.test.Test;

import java.util.List;
import java.util.Map;

public final class PersianAnalyzerTest {

    private String stem(com.naqqa.elasticsearch.analysis.stem.Stemmer stemmer, String word) {
        StringBuilder sb = new StringBuilder(word);
        stemmer.stem(sb);
        return sb.toString();
    }

    @Test
    public void arabicNormalizerFoldsAlefHamzaAbove() {
        Assert.assertEquals("ا", stem(new ArabicNormalizer(), "أ"));
    }

    @Test
    public void arabicNormalizerFoldsAlefMadda() {
        Assert.assertEquals("ا", stem(new ArabicNormalizer(), "آ"));
    }

    @Test
    public void arabicNormalizerFoldsDotlessYeh() {
        Assert.assertEquals("ي", stem(new ArabicNormalizer(), "ى"));
    }

    @Test
    public void arabicNormalizerFoldsTehMarbuta() {
        Assert.assertEquals("ه", stem(new ArabicNormalizer(), "ة"));
    }

    @Test
    public void arabicNormalizerRemovesTatweel() {
        Assert.assertEquals("بب", stem(new ArabicNormalizer(), "بـب"));
    }

    @Test
    public void arabicNormalizerRemovesFatha() {
        Assert.assertEquals("ب", stem(new ArabicNormalizer(), "بَ"));
    }

    @Test
    public void persianNormalizerFoldsFarsiYeh() {
        Assert.assertEquals("ي", stem(new PersianNormalizer(), "ی"));
    }

    @Test
    public void persianNormalizerFoldsKeheh() {
        Assert.assertEquals("ك", stem(new PersianNormalizer(), "ک"));
    }

    @Test
    public void persianNormalizerFoldsHehGoal() {
        Assert.assertEquals("ه", stem(new PersianNormalizer(), "ہ"));
    }

    @Test
    public void persianNormalizerRemovesHamzaAbove() {
        Assert.assertEquals("ب", stem(new PersianNormalizer(), "بٔ"));
    }

    @Test
    public void persianNormalizerRemovesZeroWidthNonJoiner() {
        Assert.assertEquals("ب", stem(new PersianNormalizer(), "ب‌"));
    }

    @Test
    public void persianCharFilterReplacesZeroWidthNonJoinerWithSpace() {
        PersianCharFilter filter = new PersianCharFilter();
        String result = filter.apply("می‌رود");
        Assert.assertEquals("می رود", result);
    }

    @Test
    public void persianAnalyzerRemovesStopwordsAndNormalizesFullSentence() {
        Analyzer analyzer = PersianAnalyzerFactory.create(Map.of());
        List<String> terms = analyzer.analyze(null, "خانه و کتاب");
        Assert.assertEquals(List.of("خانه", "كتاب"), terms);
    }
}
