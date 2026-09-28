package com.naqqa.elasticsearch.analysis.lang.a;

import com.naqqa.elasticsearch.analysis.Analyzer;
import com.naqqa.elasticsearch.analysis.stem.Stemmer;
import com.naqqa.elasticsearch.test.Assert;
import com.naqqa.elasticsearch.test.Test;

import java.util.List;
import java.util.Map;

public final class ArmenianAnalyzerFactoryTest {

    private static final String[][] VOCABULARY = {
        {"պահումներ", "պահ"},
        {"բուսաբանների", "բուսաբ"},
        {"հետաձգեցին", "հետաձգ"},
        {"հումանիստների", "հումանիստ"},
        {"չվերթեր", "չվերթեր"},
        {"երկնագույն", "երկնագույ"},
        {"ֆրիզերեն", "ֆրիզերե"},
        {"կասկածներին", "կասկած"},
        {"յոդաջրածնական", "յոդաջրածն"},
        {"պլատֆորմներում", "պլատֆորմ"},
        {"կրտսերի", "կրտսերի"},
        {"հաղթահարեր", "հաղթահ"},
        {"գրանուլաները", "գրանուլ"},
        {"ծանրաբեռնում", "ծանրաբեռն"},
        {"թուլությունը", "թուլ"},
        {"բացարձակ", "բացարձ"},
        {"կուրսերում", "կուրսեր"},
        {"ռադ", "ռադ"},
        {"հրվանդանները", "հրվանդ"},
    };

    @Test
    public void stemsOfficialVocabularySamples() {
        Stemmer stemmer = ArmenianAnalyzerFactory.stemmer();
        for (String[] pair : VOCABULARY) {
            Assert.assertEquals(pair[1], stemmer.stem(pair[0]), "stem(" + pair[0] + ")");
        }
    }

    @Test
    public void analyzerRemovesStopwordsAndStemsSentence() {
        Analyzer analyzer = ArmenianAnalyzerFactory.create(Map.of());
        List<String> terms = analyzer.analyze(null,
            "այս պահումները և "
                + "բուսաբանները են "
                + "հետաձգեցին");
        Assert.assertFalse(terms.contains("այս"));
        Assert.assertFalse(terms.contains("և"));
        Assert.assertFalse(terms.contains("են"));
        Assert.assertEquals(
            List.of("պահ", "բուսաբ", "հետաձգ"),
            terms);
    }
}
