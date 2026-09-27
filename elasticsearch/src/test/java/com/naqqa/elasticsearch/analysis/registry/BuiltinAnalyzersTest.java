package com.naqqa.elasticsearch.analysis.registry;

import com.naqqa.elasticsearch.analysis.Analyzer;
import com.naqqa.elasticsearch.test.Assert;
import com.naqqa.elasticsearch.test.Test;

import java.util.List;

public final class BuiltinAnalyzersTest {

    @Test
    public void standardAnalyzerMatchesElasticsearchExample() {
        Analyzer analyzer = BuiltinAnalyzers.get("standard");
        List<String> terms = analyzer.analyze(null, "The 2 QUICK Brown-Foxes jumped over the lazy dog's bone.");
        Assert.assertEquals(
            List.of("the", "2", "quick", "brown", "foxes", "jumped", "over", "the", "lazy", "dog's", "bone"),
            terms);
    }

    @Test
    public void whitespaceAnalyzerSplitsOnWhitespaceOnly() {
        Analyzer analyzer = BuiltinAnalyzers.get("whitespace");
        List<String> terms = analyzer.analyze(null, "Quick Brown-Fox!");
        Assert.assertEquals(List.of("Quick", "Brown-Fox!"), terms);
    }

    @Test
    public void englishAnalyzerStemsAndRemovesStopwords() {
        Analyzer analyzer = BuiltinAnalyzers.get("english");
        List<String> terms = analyzer.analyze(null, "The runners are running quickly");
        Assert.assertTrue(terms.contains("runner") || terms.contains("run"));
        Assert.assertFalse(terms.contains("the"));
        Assert.assertFalse(terms.contains("are"));
    }
}
