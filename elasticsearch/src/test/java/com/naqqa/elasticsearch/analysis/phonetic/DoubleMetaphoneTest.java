package com.naqqa.elasticsearch.analysis.phonetic;

import static com.naqqa.elasticsearch.test.Assert.assertEquals;
import static com.naqqa.elasticsearch.test.Assert.assertFalse;
import static com.naqqa.elasticsearch.test.Assert.assertNull;
import static com.naqqa.elasticsearch.test.Assert.assertTrue;

import com.naqqa.elasticsearch.test.Test;

public class DoubleMetaphoneTest {

    private final DoubleMetaphone encoder = new DoubleMetaphone();

    public DoubleMetaphoneTest() {
    }

    private void primary(String expected, String source) {
        assertEquals(expected, encoder.encode(source), source);
        assertEquals(expected, encoder.doubleMetaphone(source, false), source);
    }

    private void alternate(String expected, String source) {
        assertEquals(expected, encoder.encodeAlternate(source), source);
        assertEquals(expected, encoder.doubleMetaphone(source, true), source);
    }

    @Test
    public void testPrimary() {
        primary("TSTN", "testing");
        primary("0", "The");
        primary("KK", "quick");
        primary("PRN", "brown");
        primary("FKS", "fox");
        primary("JMPT", "jumped");
        primary("AFR", "over");
        primary("0", "the");
        primary("LS", "lazy");
        primary("TKS", "dogs");
        primary("MKFR", "MacCafferey");
        primary("STFN", "Stephan");
        primary("KSSK", "Kuczewski");
        primary("MKLL", "McClelland");
        primary("SNHS", "san jose");
        primary("SNFP", "xenophobia");
    }

    @Test
    public void testAlternate() {
        alternate("TSTN", "testing");
        alternate("T", "The");
        alternate("KK", "quick");
        alternate("PRN", "brown");
        alternate("FKS", "fox");
        alternate("AMPT", "jumped");
        alternate("AFR", "over");
        alternate("T", "the");
        alternate("LS", "lazy");
        alternate("TKS", "dogs");
        alternate("MKFR", "MacCafferey");
        alternate("STFN", "Stephan");
        alternate("KXFS", "Kutchefski");
        alternate("MKLL", "McClelland");
        alternate("SNHS", "san jose");
        alternate("SNFP", "xenophobia");
        alternate("FKR", "Fokker");
        alternate("AK", "Joqqi");
        alternate("HF", "Hovvi");
        alternate("XRN", "Czerny");
    }

    @Test
    public void testEmpty() {
        assertNull(encoder.encode(null));
        assertNull(encoder.encode(""));
        assertNull(encoder.encode(" "));
        assertNull(encoder.encode("\t\n\r "));
        assertNull(encoder.encodeAlternate(null));
        assertNull(encoder.encode("123"));
        assertEquals("", encoder.doubleMetaphone("123"));
    }

    @Test
    public void testEqualBasic() {
        String[][] fixture = {{"", ""}, {"Case", "case"}, {"CASE", "Case"}, {"caSe", "cAsE"}, {"cookie", "quick"}, {"quick", "cookie"},
            {"Brian", "Bryan"}, {"Auto", "Otto"}, {"Steven", "Stefan"}, {"Philipowitz", "Filipowicz"}};
        for (boolean alt : new boolean[] {false, true}) {
            for (String[] pair : fixture) {
                assertTrue(encoder.isDoubleMetaphoneEqual(pair[0], pair[1], alt), pair[0] + " " + pair[1]);
                assertTrue(encoder.isDoubleMetaphoneEqual(pair[1], pair[0], alt), pair[0] + " " + pair[1]);
            }
        }
        assertTrue(encoder.isDoubleMetaphoneEqual("Jablonski", "Yablonsky", true));
        assertTrue(encoder.isDoubleMetaphoneEqual("ANGHELINA", "ANKL", false));
    }

    @Test
    public void testNotEqual() {
        assertFalse(encoder.isDoubleMetaphoneEqual("Brain", "Band", false));
        assertFalse(encoder.isDoubleMetaphoneEqual("Band", "Brain", true));
        assertFalse(encoder.isDoubleMetaphoneEqual("aa", "", false));
        assertFalse(encoder.isDoubleMetaphoneEqual("", "aa", true));
    }

    @Test
    public void testSpecialCharacters() {
        assertTrue(encoder.isDoubleMetaphoneEqual("ç", "S"));
        assertTrue(encoder.isDoubleMetaphoneEqual("ñ", "N"));
    }

    @Test
    public void testMatches() {
        int count = 0;
        for (String[] pair : DoubleMetaphoneMatches.MATCHES) {
            boolean primaryMatch = encoder.isDoubleMetaphoneEqual(pair[0], pair[1], false);
            boolean alternateMatch = encoder.isDoubleMetaphoneEqual(pair[0], pair[1], true);
            assertTrue(primaryMatch || alternateMatch, pair[0] + " / " + pair[1]);
            count++;
        }
        assertTrue(count > 400);
    }

    @Test
    public void testMaxCodeLen() {
        DoubleMetaphone dm = new DoubleMetaphone();
        assertEquals(4, dm.getMaxCodeLen());
        assertEquals("JMPT", dm.encode("jumped"));
        assertEquals("AMPT", dm.encodeAlternate("jumped"));
        dm.setMaxCodeLen(3);
        assertEquals("JMP", dm.encode("jumped"));
        assertEquals("AMP", dm.encodeAlternate("jumped"));
        dm.setMaxCodeLen(6);
        assertEquals("SPRKLF", dm.encode("supercalifragilisticexpialidocious"));
    }
}
