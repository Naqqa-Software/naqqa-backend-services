package com.naqqa.elasticsearch.analysis.phonetic;

import static com.naqqa.elasticsearch.test.Assert.assertEquals;
import static com.naqqa.elasticsearch.test.Assert.assertNull;
import static com.naqqa.elasticsearch.test.Assert.assertTrue;

import com.naqqa.elasticsearch.test.Test;

public class NysiisTest {

    private final Nysiis strict = new Nysiis();
    private final Nysiis full = new Nysiis(false);

    public NysiisTest() {
    }

    private static void check(Nysiis encoder, String[][] cases) {
        for (String[] c : cases) {
            assertEquals(c[1], encoder.encode(c[0]), c[0]);
        }
    }

    @Test
    public void testStrictDefault() {
        assertTrue(strict.isStrict());
        check(strict, new String[][] {{"Brian", "BRAN"}, {"Brown", "BRAN"}, {"Brun", "BRAN"}, {"Capp", "CAP"}, {"Cope", "CAP"},
            {"Copp", "CAP"}, {"Kipp", "CAP"}, {"Dent", "DAD"}, {"Dane", "DAN"}, {"Dean", "DAN"}, {"Dionne", "DAN"}, {"Phil", "FAL"},
            {"Schmidt", "SNAD"}, {"Smith", "SNAT"}, {"Schmit", "SNAT"}, {"Trueman", "TRANAN"}, {"Truman", "TRANAN"},
            {"WESTERLUND", "WASTAR"}, {"Kobwick", "CABWAC"}, {"Kocher", "CACAR"}, {"Fesca", "FASC"}, {"Shom", "SAN"}, {"Ohlo", "OL"},
            {"Uhu", "UH"}, {"Um", "UN"}});
    }

    @Test
    public void testDropBy() {
        check(full, new String[][] {{"MACINTOSH", "MCANT"}, {"KNUTH", "NAT"}, {"KOEHN", "CAN"}, {"PHILLIPSON", "FALAPSAN"},
            {"PFEISTER", "FASTAR"}, {"SCHOENHOEFT", "SANAFT"}, {"MCKEE", "MCY"}, {"MACKIE", "MCY"}, {"HEITSCHMIDT", "HATSNAD"},
            {"BART", "BAD"}, {"HURD", "HAD"}, {"HUNT", "HAD"}, {"WESTERLUND", "WASTARLAD"}, {"CASSTEVENS", "CASTAFAN"},
            {"VASQUEZ", "VASG"}, {"FRAZIER", "FRASAR"}, {"BOWMAN", "BANAN"}, {"MCKNIGHT", "MCNAGT"}, {"RICKERT", "RACAD"},
            {"DEUTSCH", "DAT"}, {"WESTPHAL", "WASTFAL"}, {"SHRIVER", "SRAVAR"}, {"KUHL", "CAL"}, {"RAWSON", "RASAN"}, {"JILES", "JAL"},
            {"CARRAWAY", "CARY"}, {"YAMADA", "YANAD"}});
    }

    @Test
    public void testOthersAndRules() {
        check(full, new String[][] {{"O'Daniel", "ODANAL"}, {"O'Donnel", "ODANAL"}, {"Cory", "CARY"}, {"Corey", "CARY"},
            {"Kory", "CARY"}, {"FUZZY", "FASY"}, {"MACX", "MCX"}, {"KNX", "NX"}, {"KX", "CX"}, {"PHX", "FX"}, {"PFX", "FX"},
            {"SCHX", "SX"}, {"XEE", "XY"}, {"XIE", "XY"}, {"XDT", "XD"}, {"XRT", "XD"}, {"XRD", "XD"}, {"XNT", "XD"}, {"XND", "XD"},
            {"XEV", "XAF"}, {"XAX", "XAX"}, {"XEX", "XAX"}, {"XIX", "XAX"}, {"XOX", "XAX"}, {"XUX", "XAX"}, {"XQ", "XG"}, {"XZ", "X"},
            {"XM", "XN"}, {"XS", "X"}, {"XSS", "X"}, {"XAY", "XY"}, {"XAYS", "XY"}, {"XA", "X"}, {"XAS", "X"}});
    }

    @Test
    public void testNoOutput() {
        assertNull(strict.encode(null));
        assertNull(strict.encode(""));
        assertNull(strict.encode("  "));
        assertNull(strict.encode("1234"));
    }
}
