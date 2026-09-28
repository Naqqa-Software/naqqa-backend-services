package com.naqqa.elasticsearch.analysis.lang.a;

import com.naqqa.elasticsearch.analysis.Analyzer;
import com.naqqa.elasticsearch.analysis.stem.Stemmer;
import com.naqqa.elasticsearch.test.Assert;
import com.naqqa.elasticsearch.test.Test;

import java.util.List;
import java.util.Map;

public final class TurkishAnalyzerFactoryTest {

    private static final String[][] VOCABULARY = {
        {"koleji", "kolej"},
        {"plaja", "plaj"},
        {"kulisi", "kulis"},
        {"yerlerimiz", "yer"},
        {"posterlerin", "poster"},
        {"kriteri'", "kriter"},
        {"yönetimden", "yönet"},
        {"yanıkları", "yanık"},
        {"sektirme", "sektirme"},
        {"maaşi", "maaşi"},
        {"seyredemeyiz", "seyrede"},
        {"mevsim", "mevs"},
        {"şırnakspor'dan", "şırnakspor"},
        {"yandaşlığıdır", "yandaşlık"},
        {"hükmünün", "hükmü"},
        {"alınacaktı", "alınacak"},
        {"ignacio", "ignacio"},
        {"ze'ye", "ze"},
    };

    @Test
    public void stemsOfficialVocabularySamples() {
        Stemmer stemmer = TurkishAnalyzerFactory.stemmer();
        for (String[] pair : VOCABULARY) {
            Assert.assertEquals(pair[1], stemmer.stem(pair[0]), "stem(" + pair[0] + ")");
        }
    }

    @Test
    public void analyzerAppliesApostropheDottedLowercaseAndStemsSentence() {
        Analyzer analyzer = TurkishAnalyzerFactory.create(Map.of());
        Assert.assertEquals(List.of("ağaç"), analyzer.analyze(null, "ağacı"));
        Assert.assertEquals(List.of(), analyzer.analyze(null, "dolayı"));
        Assert.assertEquals(List.of("kıbrıs"), analyzer.analyze(null, "Kıbrıs'ta"));
        Assert.assertEquals(List.of("van", "göl"), analyzer.analyze(null, "Van Gölü'ne"));
    }

    @Test
    public void stemExclusionKeepsWordUnstemmed() {
        Analyzer analyzer = TurkishAnalyzerFactory.create(Map.of("stem_exclusion", List.of("ağacı")));
        Assert.assertEquals(List.of("ağacı"), analyzer.analyze(null, "ağacı"));
    }
}
