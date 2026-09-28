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

    @Test
    public void russianAnalyzerStemsAndRemovesStopwords() {
        Analyzer analyzer = BuiltinAnalyzers.get("russian");
        List<String> terms = analyzer.analyze(null,
            "дети читают интересные "
                + "книги в большом доме");
        Assert.assertEquals(
            List.of(
                "дет",
                "чита",
                "интересн",
                "книг",
                "больш",
                "дом"),
            terms);
        Assert.assertFalse(terms.contains("в"));
    }

    @Test
    public void frenchAnalyzerStemsElidesAndRemovesStopwords() {
        Analyzer analyzer = BuiltinAnalyzers.get("french");
        List<String> terms = analyzer.analyze(null, "L'avion décolle de l'aéroport et les passagers sont contents");
        Assert.assertEquals(List.of("avion", "decol", "aeroport", "pasag", "content"), terms);
        Assert.assertFalse(terms.contains("l'avion"));
        Assert.assertFalse(terms.contains("de"));
        Assert.assertFalse(terms.contains("et"));
        Assert.assertFalse(terms.contains("les"));
        Assert.assertFalse(terms.contains("sont"));
    }

    @Test
    public void romanianAnalyzerStemsAndRemovesStopwords() {
        Analyzer analyzer = BuiltinAnalyzers.get("romanian");
        List<String> terms = analyzer.analyze(null,
            "Copiii care citesc cărțile frumoase din bibliotecă sunt fericiti");
        Assert.assertEquals(
            List.of("copii", "citesc", "cărț", "frumoas", "bibliotec", "feric"),
            terms);
        Assert.assertFalse(terms.contains("care"));
        Assert.assertFalse(terms.contains("din"));
        Assert.assertFalse(terms.contains("sunt"));
    }
}
