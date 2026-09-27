package com.naqqa.elasticsearch.analysis.stop;

import static com.naqqa.elasticsearch.test.Assert.assertEquals;
import static com.naqqa.elasticsearch.test.Assert.assertFalse;
import static com.naqqa.elasticsearch.test.Assert.assertNull;
import static com.naqqa.elasticsearch.test.Assert.assertTrue;

import com.naqqa.elasticsearch.test.Assert;
import com.naqqa.elasticsearch.test.Test;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

public class StopwordListsTest {

    public StopwordListsTest() {
    }

    @Test
    public void english() {
        List<String> en = StopwordLists.get("_english_");
        assertEquals(33, en.size());
        assertEquals("a", en.get(0));
        assertTrue(en.contains("the"));
        assertTrue(en.contains("with"));
        assertFalse(en.contains("i"));
    }

    @Test
    public void listSizes() {
        Map<String, Integer> expected = Map.ofEntries(
            Map.entry("_arabic_", 119), Map.entry("_armenian_", 45), Map.entry("_basque_", 98),
            Map.entry("_bengali_", 116), Map.entry("_brazilian_", 127), Map.entry("_bulgarian_", 190),
            Map.entry("_catalan_", 218), Map.entry("_cjk_", 35), Map.entry("_czech_", 171),
            Map.entry("_danish_", 94), Map.entry("_dutch_", 101), Map.entry("_english_", 33),
            Map.entry("_estonian_", 1470), Map.entry("_finnish_", 229), Map.entry("_french_", 154),
            Map.entry("_galician_", 160), Map.entry("_german_", 231), Map.entry("_greek_", 75),
            Map.entry("_hindi_", 225), Map.entry("_hungarian_", 198), Map.entry("_indonesian_", 355),
            Map.entry("_irish_", 109), Map.entry("_italian_", 279), Map.entry("_latvian_", 161),
            Map.entry("_lithuanian_", 125), Map.entry("_none_", 0), Map.entry("_norwegian_", 172),
            Map.entry("_persian_", 308), Map.entry("_portuguese_", 203), Map.entry("_romanian_", 254),
            Map.entry("_russian_", 159), Map.entry("_serbian_", 156), Map.entry("_sorani_", 62),
            Map.entry("_spanish_", 308), Map.entry("_swedish_", 114), Map.entry("_thai_", 115),
            Map.entry("_turkish_", 209));
        assertEquals(expected.keySet(), new HashSet<>(StopwordLists.names()));
        for (Map.Entry<String, Integer> e : expected.entrySet()) {
            List<String> list = StopwordLists.get(e.getKey());
            assertEquals((long) e.getValue(), (long) list.size());
            assertEquals(list.size(), new HashSet<>(list).size(), "duplicates in " + e.getKey());
            for (String w : list) {
                assertFalse(w.isEmpty() || w.contains(" ") || (w.contains("|") && !e.getKey().equals("_estonian_")) ||w.startsWith("#"), e.getKey() + ": " + w);
            }
        }
    }

    @Test
    public void contents() {
        assertTrue(StopwordLists.get("_french_").contains("était"));
        assertFalse(StopwordLists.get("_french_").contains("été"));
        assertTrue(StopwordLists.get("_estonian_").contains("killadi|-kolladi"));
        assertTrue(StopwordLists.get("_french_").contains("aux"));
        assertTrue(StopwordLists.get("_german_").contains("über"));
        assertTrue(StopwordLists.get("_german_").contains("und"));
        assertTrue(StopwordLists.get("_spanish_").contains("él"));
        assertTrue(StopwordLists.get("_italian_").contains("perché"));
        assertTrue(StopwordLists.get("_portuguese_").contains("não"));
        assertTrue(StopwordLists.get("_dutch_").contains("het"));
        assertTrue(StopwordLists.get("_russian_").contains("что"));
        assertTrue(StopwordLists.get("_swedish_").contains("och"));
        assertTrue(StopwordLists.get("_norwegian_").contains("og"));
        assertTrue(StopwordLists.get("_danish_").contains("og"));
        assertTrue(StopwordLists.get("_finnish_").contains("olla"));
        assertTrue(StopwordLists.get("_arabic_").contains("من"));
        assertTrue(StopwordLists.get("_greek_").contains("και"));
        assertTrue(StopwordLists.get("_cjk_").contains("www"));
        assertTrue(StopwordLists.get("_turkish_").contains("ve"));
    }

    @Test
    public void unknownAndImmutable() {
        assertNull(StopwordLists.get("_klingon_"));
        assertNull(StopwordLists.get(null));
        assertFalse(StopwordLists.isKnown("_klingon_"));
        assertTrue(StopwordLists.isKnown("_none_"));
        assertTrue(StopwordLists.get("_none_").isEmpty());
        Assert.assertThrows(UnsupportedOperationException.class, () -> StopwordLists.get("_english_").add("x"));
    }
}
