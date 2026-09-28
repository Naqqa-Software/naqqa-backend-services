package com.naqqa.elasticsearch.analysis.lang.a;

import com.naqqa.elasticsearch.analysis.Analyzer;
import com.naqqa.elasticsearch.analysis.stem.Stemmer;
import com.naqqa.elasticsearch.test.Assert;
import com.naqqa.elasticsearch.test.Test;

import java.util.List;
import java.util.Map;

public final class BasqueAnalyzerFactoryTest {

    private static final String[][] VOCABULARY = {
        {"funtsezko", "funtse"},
        {"taldera", "tald"},
        {"doktorea", "doktorea"},
        {"grabitatea", "grabi"},
        {"zeritzona", "zeri"},
        {"urgazlea", "urgaz"},
        {"suntsitzeko", "suntsi"},
        {"zenbatespenak", "zenbatesp"},
        {"konpainiaren", "konpainia"},
        {"egurrezko", "egurre"},
        {"gaztelania", "gaztelania"},
        {"gatibu", "gatibu"},
        {"euskarari", "eusk"},
        {"islatzeko", "isla"},
        {"plaka", "pla"},
        {"praktiko", "prak"},
        {"jurisdikzio", "jurisd"},
        {"ospetsuena", "ospe"},
        {"ezkerreko", "ezkerr"},
    };

    @Test
    public void stemsOfficialVocabularySamples() {
        Stemmer stemmer = BasqueAnalyzerFactory.stemmer();
        for (String[] pair : VOCABULARY) {
            Assert.assertEquals(pair[1], stemmer.stem(pair[0]), "stem(" + pair[0] + ")");
        }
    }

    @Test
    public void analyzerRemovesStopwordsAndStemsSentence() {
        Analyzer analyzer = BasqueAnalyzerFactory.create(Map.of());
        List<String> terms = analyzer.analyze(null, "Euskal Herriko ikasleak eskolara joaten dira egunero autobusetan");
        Assert.assertFalse(terms.contains("dira"));
        Assert.assertTrue(terms.contains("euskal"));
        Assert.assertTrue(terms.contains("herri"));
        Assert.assertTrue(terms.contains("ikasle"));
        Assert.assertTrue(terms.contains("eskol"));
    }
}
