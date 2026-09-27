package com.naqqa.elasticsearch.analysis.phonetic;

import static com.naqqa.elasticsearch.test.Assert.assertEquals;
import static com.naqqa.elasticsearch.test.Assert.assertNull;
import static com.naqqa.elasticsearch.test.Assert.assertThrows;

import com.naqqa.elasticsearch.test.Test;

public class SoundexTest {

    private final Soundex soundex = new Soundex();

    public SoundexTest() {
    }

    private void check(String expected, String... inputs) {
        for (String input : inputs) {
            assertEquals(expected, soundex.encode(input), input);
        }
    }

    @Test
    public void testB650() {
        check("B650", "BARHAM", "BARONE", "BARRON", "BERNA", "BIRNEY", "BIRNIE", "BOOROM", "BOREN", "BORN", "BOURN", "BOURNE",
            "BOWRON", "BRAIN", "BRAME", "BRANN", "BRAUN", "BREEN", "BRIEN", "BRIM", "BRIMM", "BRINN", "BRION", "BROOM", "BROOME",
            "BROWN", "BROWNE", "BRUEN", "BRUHN", "BRUIN", "BRUMM", "BRUN", "BRUNO", "BRYAN", "BURIAN", "BURN", "BURNEY", "BYRAM",
            "BYRNE", "BYRON", "BYRUM");
    }

    @Test
    public void testBadCharacters() {
        check("H452", "HOL>MES");
    }

    @Test
    public void testEncodeBasic() {
        check("T235", "testing");
        check("T000", "The", "the");
        check("Q200", "quick");
        check("B650", "brown");
        check("F200", "fox");
        check("J513", "jumped");
        check("O160", "over");
        check("L200", "lazy");
        check("D200", "dogs");
    }

    @Test
    public void testEncodeBatch2() {
        check("A462", "Allricht");
        check("E166", "Eberhard");
        check("E521", "Engebrethson");
        check("H512", "Heimbach");
        check("H524", "Hanselmann");
        check("H431", "Hildebrand");
        check("K152", "Kavanagh");
        check("L530", "Lind");
        check("L222", "Lukaschowsky");
        check("M235", "McDonnell");
        check("M200", "McGee");
        check("O155", "Opnian", "Oppenheimer");
        check("R355", "Riedemanas");
        check("Z300", "Zita");
        check("Z325", "Zitzmeinn");
    }

    @Test
    public void testEncodeBatch3And4() {
        check("W252", "Washington", " \t\n\r Washington \t\n\r ");
        check("L000", "Lee");
        check("G362", "Gutierrez");
        check("P236", "Pfister");
        check("J250", "Jackson", "JACKSON");
        check("T522", "Tymczak");
        check("V532", "VanDeusen");
        check("H452", "HOLMES");
        check("A355", "ADOMOMI");
        check("V536", "VONDERLEHR");
        check("B400", "BALL");
        check("S000", "SHAW");
        check("S545", "SCANLON");
        check("S532", "SAINTJOHN");
    }

    @Test
    public void testIgnoreApostrophesAndHyphens() {
        check("O165", "OBrien", "'OBrien", "O'Brien", "OB'rien", "OBr'ien", "OBri'en", "OBrie'n", "OBrien'");
        check("K525", "KINGSMITH", "-KINGSMITH", "K-INGSMITH", "KI-NGSMITH", "KIN-GSMITH", "KING-SMITH", "KINGS-MITH",
            "KINGSM-ITH", "KINGSMI-TH", "KINGSMIT-H", "KINGSMITH-");
    }

    @Test
    public void testHWRule() {
        check("A261", "Ashcraft", "Ashcroft");
        check("Y330", "yehudit", "yhwdyt");
        check("B312", "BOOTHDAVIS", "BOOTH-DAVIS");
        check("S460", "Sgler", "Swhgler", "SAILOR", "SALYER", "SAYLOR", "SCHALLER", "SCHELLER", "SCHILLER", "SCHOOLER", "SCHULER",
            "SCHUYLER", "SEILER", "SEYLER", "SHOLAR", "SHULER", "SILAR", "SILER", "SILLER");
    }

    @Test
    public void testMsSqlServer() {
        check("S530", "Smith", "Smythe");
        check("E625", "Erickson", "Erikson", "Ericson", "Ericksen", "Ericsen");
        check("A500", "Ann", "Anne");
        check("A536", "Andrew");
        check("J530", "Janet");
        check("M626", "Margaret");
        check("S315", "Steven");
        check("M240", "Michael");
        check("R163", "Robert", "Rupert");
        check("L600", "Laura");
        check("W452", "Williams");
    }

    @Test
    public void testDifference() {
        assertEquals(0, soundex.difference(null, null));
        assertEquals(0, soundex.difference("", ""));
        assertEquals(0, soundex.difference(" ", " "));
        assertEquals(4, soundex.difference("Smith", "Smythe"));
        assertEquals(2, soundex.difference("Ann", "Andrew"));
        assertEquals(1, soundex.difference("Margaret", "Andrew"));
        assertEquals(0, soundex.difference("Janet", "Margaret"));
        assertEquals(4, soundex.difference("Green", "Greene"));
        assertEquals(0, soundex.difference("Blotchet-Halls", "Greene"));
        assertEquals(4, soundex.difference("Smithers", "Smythers"));
        assertEquals(2, soundex.difference("Anothers", "Brothers"));
    }

    @Test
    public void testGenealogy() {
        Soundex s = Soundex.genealogy();
        assertEquals("H251", s.encode("Heggenburger"));
        assertEquals("B425", s.encode("Blackman"));
        assertEquals("S530", s.encode("Schmidt"));
        assertEquals("L150", s.encode("Lippmann"));
        assertEquals("D200", s.encode("Dodds"));
        assertEquals("D200", s.encode("Dhdds"));
        assertEquals("D200", s.encode("Dwdds"));
    }

    @Test
    public void testSimplified() {
        Soundex s = Soundex.simplified();
        assertEquals("W452", s.encode("WILLIAMS"));
        assertEquals("B625", s.encode("BARAGWANATH"));
        assertEquals("D540", s.encode("DONNELL"));
        assertEquals("L300", s.encode("LLOYD"));
        assertEquals("W422", s.encode("WOOLCOCK"));
        assertEquals("D320", s.encode("Dodds"));
        assertEquals("D320", s.encode("Dwdds"));
        assertEquals("D320", s.encode("Dhdds"));
    }

    @Test
    public void testNoOutput() {
        assertNull(soundex.encode(null));
        assertNull(soundex.encode(""));
        assertNull(soundex.encode(" "));
        assertNull(soundex.encode("123-!"));
    }

    @Test
    public void testNonAsciiLettersRejected() {
        assertEquals("E000", soundex.encode("e"));
        assertThrows(IllegalArgumentException.class, () -> soundex.encode("é"));
        assertThrows(IllegalArgumentException.class, () -> soundex.encode("ö"));
    }
}
