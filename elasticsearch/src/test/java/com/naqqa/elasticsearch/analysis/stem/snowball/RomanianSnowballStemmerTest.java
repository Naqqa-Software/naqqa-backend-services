package com.naqqa.elasticsearch.analysis.stem.snowball;

import com.naqqa.elasticsearch.test.Assert;
import com.naqqa.elasticsearch.test.Test;

public final class RomanianSnowballStemmerTest {

    private static final String[][] PAIRS = {
        {"copil", "copil"},
        {"copilul", "copil"},
        {"copilului", "copil"},
        {"copiii", "copii"},
        {"copiilor", "cop"},
        {"case", "cas"},
        {"casa", "cas"},
        {"casei", "case"},
        {"caselor", "cas"},
        {"fete", "fet"},
        {"fata", "fat"},
        {"lucra", "lucr"},
        {"lucrez", "lucr"},
        {"lucram", "lucr"},
        {"lucrat", "lucrat"},
        {"frumos", "frumos"},
        {"frumoase", "frumoas"},
        {"prieten", "prieten"},
        {"prietenul", "prieten"},
        {"prietenii", "prieten"},
        {"prietenilor", "prieten"},
        {"carte", "cart"},
        {"cartea", "cart"},
        {"lucru", "lucru"},
        {"lucruri", "lucrur"},
        {"lucrurile", "lucrur"},
        {"student", "student"},
        {"studentul", "student"},
        {"citi", "cit"},
        {"citesc", "citesc"},
        {"citim", "cit"},
        {"citit", "citit"},
        {"vorbi", "vorb"},
        {"vorbesc", "vorb"},
        {"vorbim", "vorb"},
        {"vorbit", "vorbit"}
    };

    @Test
    public void matchesOfficialSnowballVocabulary() {
        RomanianSnowballStemmer stemmer = new RomanianSnowballStemmer();
        for (String[] pair : PAIRS) {
            Assert.assertEquals(pair[1], stemmer.stem(pair[0]), "stem(" + pair[0] + ")");
        }
    }

    @Test
    public void cedillaAndCommaBelowSpellingsStemIdentically() {
        RomanianSnowballStemmer stemmer = new RomanianSnowballStemmer();
        Assert.assertEquals("abandon", stemmer.stem("abandonați"));
        Assert.assertEquals("abandon", stemmer.stem("abandonaţi"));
        Assert.assertEquals("abol", stemmer.stem("abolește"));
        Assert.assertEquals("abol", stemmer.stem("aboleşte"));
        Assert.assertEquals(stemmer.stem("acești"), stemmer.stem("aceşti"));
        Assert.assertEquals(stemmer.stem("mașina"), stemmer.stem("maşina"));
    }
}
