package com.naqqa.elasticsearch.analysis.registry;

import com.naqqa.elasticsearch.analysis.Analyzer;
import com.naqqa.elasticsearch.analysis.stem.Stemmers;
import com.naqqa.elasticsearch.test.Assert;
import com.naqqa.elasticsearch.test.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class LanguageRegistrationTest {

    private static Map<String, String> samples() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("arabic", "الأطفال يلعبون في الحديقة");
        m.put("armenian", "Երեխաները խաղում են այգում");
        m.put("basque", "Haurrak parkean jolasten ari dira");
        m.put("catalan", "Els nens juguen al parc de l'escola");
        m.put("hungarian", "A gyerekek a parkban játszanak");
        m.put("indonesian", "Anak-anak bermain di taman");
        m.put("irish", "Tá na páistí ag súgradh sa pháirc");
        m.put("turkish", "Çocuklar parkta oynuyorlar");
        m.put("brazilian", "As crianças estão brincando no parque");
        m.put("bulgarian", "Децата играят в парка");
        m.put("czech", "Děti si hrají v parku");
        m.put("estonian", "Lapsed mängivad pargis");
        m.put("galician", "Os nenos xogan no parque");
        m.put("greek", "Τα παιδιά παίζουν στο πάρκο");
        m.put("latvian", "Bērni spēlējas parkā");
        m.put("lithuanian", "Vaikai žaidžia parke");
        m.put("serbian", "Деца се играју у парку");
        m.put("hindi", "बच्चे पार्क में खेल रहे हैं");
        m.put("bengali", "শিশুরা পার্কে খেলছে");
        m.put("persian", "بچه‌ها در پارک بازی می‌کنند");
        m.put("sorani", "منداڵەکان لە پارکەکە یاری دەکەن");
        m.put("thai", "เด็กเล่นในสวน");
        return m;
    }

    @Test
    public void everyNewLanguageAnalyzerIsRegisteredAndProducesTokens() {
        for (Map.Entry<String, String> e : samples().entrySet()) {
            Analyzer analyzer = BuiltinAnalyzers.get(e.getKey());
            Assert.assertNotNull(analyzer);
            List<String> terms = analyzer.analyze(null, e.getValue());
            Assert.assertFalse(terms.isEmpty(), e.getKey() + " produced no tokens");
        }
    }

    @Test
    public void newStemmerLanguagesResolve() {
        for (String lang : List.of("arabic", "armenian", "basque", "catalan", "hungarian", "indonesian", "irish", "turkish", "brazilian", "bulgarian", "czech", "estonian", "greek", "latvian",
            "lithuanian", "serbian", "hindi", "bengali", "sorani")) {
            Assert.assertNotNull(Stemmers.create(lang));
        }
    }

    @Test
    public void serbianCyrillicAndLatinMatch() {
        Analyzer analyzer = BuiltinAnalyzers.get("serbian");
        Assert.assertEquals(analyzer.analyze(null, "парку"), analyzer.analyze(null, "parku"));
    }
}
