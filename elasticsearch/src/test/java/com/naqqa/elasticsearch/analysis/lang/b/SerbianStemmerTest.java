package com.naqqa.elasticsearch.analysis.lang.b;

import com.naqqa.elasticsearch.analysis.Analyzer;
import com.naqqa.elasticsearch.analysis.TokenStream;
import com.naqqa.elasticsearch.analysis.tokenizer.WhitespaceTokenizer;
import com.naqqa.elasticsearch.test.Assert;
import com.naqqa.elasticsearch.test.Test;

import java.util.ArrayList;
import java.util.List;

public final class SerbianStemmerTest {

    private String stem(String word) {
        StringBuilder sb = new StringBuilder(word);
        new SerbianStemmer().stem(sb);
        return sb.toString();
    }

    @Test
    public void stemsCommonVocabulary() {
        Assert.assertEquals("kuc", stem("kuca"));
        Assert.assertEquals("kuc", stem("kuce"));
        Assert.assertEquals("kuc", stem("kucama"));
        Assert.assertEquals("pisa", stem("pisati"));
        Assert.assertEquals("pisa", stem("pisao"));
        Assert.assertEquals("pisa", stem("pisala"));
        Assert.assertEquals("det", stem("dete"));
        Assert.assertEquals("detet", stem("deteta"));
        Assert.assertEquals("zen", stem("zena"));
        Assert.assertEquals("zen", stem("zene"));
        Assert.assertEquals("grad", stem("grad"));
        Assert.assertEquals("gradov", stem("gradovi"));
        Assert.assertEquals("vod", stem("voda"));
        Assert.assertEquals("vod", stem("vode"));
        Assert.assertEquals("dobar", stem("dobar"));
        Assert.assertEquals("dobr", stem("dobra"));
        Assert.assertEquals("dobr", stem("dobro"));
        Assert.assertEquals("radi", stem("raditi"));
        Assert.assertEquals("radi", stem("radio"));
        Assert.assertEquals("knjig", stem("knjiga"));
    }

    private List<String> normalize(String text) {
        WhitespaceTokenizer tokenizer = new WhitespaceTokenizer();
        tokenizer.setInput(text);
        TokenStream ts = new SerbianNormalizationFilter(tokenizer);
        List<String> out = new ArrayList<>();
        ts.reset();
        while (ts.incrementToken()) {
            out.add(ts.token().term());
        }
        ts.end();
        ts.close();
        return out;
    }

    @Test
    public void normalizationFilterMapsCyrillicAndLatinDiacriticsToPlainLatin() {
        Assert.assertEquals(List.of("kuca", "dj", "lj", "nj", "dz"), normalize("kuca đ lj nj dž"));
        Assert.assertEquals(List.of("kuca", "dj", "lj", "nj", "dz"), normalize("куча ђ љ њ џ"));
    }

    @Test
    public void cyrillicAndLatinSpellingsProduceTheSameToken() {
        Analyzer analyzer = SerbianAnalyzerFactory.create(java.util.Map.of());
        List<String> latin = analyzer.analyze(null, "Kuca je velika i lepa");
        List<String> cyrillic = analyzer.analyze(null, "Кућа је велика и лепа");
        Assert.assertEquals(latin, cyrillic);
        Assert.assertTrue(latin.contains("kuc"));
        Assert.assertTrue(latin.contains("velik"));
        Assert.assertTrue(latin.contains("lep"));
        Assert.assertFalse(latin.contains("je"));
        Assert.assertFalse(latin.contains("i"));
    }
}
