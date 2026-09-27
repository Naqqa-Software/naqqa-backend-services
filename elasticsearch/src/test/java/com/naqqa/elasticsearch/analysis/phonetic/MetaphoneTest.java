package com.naqqa.elasticsearch.analysis.phonetic;

import static com.naqqa.elasticsearch.test.Assert.assertEquals;
import static com.naqqa.elasticsearch.test.Assert.assertNull;
import static com.naqqa.elasticsearch.test.Assert.assertTrue;

import com.naqqa.elasticsearch.test.Test;

public class MetaphoneTest {

    private final Metaphone metaphone = new Metaphone();

    public MetaphoneTest() {
    }

    private void assertAllEqual(String source, String... matches) {
        for (String match : matches) {
            assertTrue(metaphone.isMetaphoneEqual(source, match), source + " vs " + match);
        }
    }

    @Test
    public void testMetaphone() {
        assertEquals("HL", metaphone.encode("howl"));
        assertEquals("TSTN", metaphone.encode("testing"));
        assertEquals("0", metaphone.encode("The"));
        assertEquals("KK", metaphone.encode("quick"));
        assertEquals("BRN", metaphone.encode("brown"));
        assertEquals("FKS", metaphone.encode("fox"));
        assertEquals("JMPT", metaphone.encode("jumped"));
        assertEquals("OFR", metaphone.encode("over"));
        assertEquals("0", metaphone.encode("the"));
        assertEquals("LS", metaphone.encode("lazy"));
        assertEquals("TKS", metaphone.encode("dogs"));
        assertEquals("J", metaphone.encode("joe"));
        assertEquals("BLKS", metaphone.encode("bloggs"));
    }

    @Test
    public void testSpecialCases() {
        assertEquals("SNS", metaphone.encode("SCIENCE"));
        assertEquals("SN", metaphone.encode("SCENE"));
        assertEquals("S", metaphone.encode("SCY"));
        assertEquals("N", metaphone.encode("GNU"));
        assertEquals("SNT", metaphone.encode("SIGNED"));
        assertEquals("KNT", metaphone.encode("GHENT"));
        assertEquals("B", metaphone.encode("BAUGH"));
        assertEquals("AKSK", metaphone.encode("AXEAXE"));
        assertEquals("FX", metaphone.encode("PHISH"));
        assertEquals("XT", metaphone.encode("SHOT"));
        assertEquals("OTXN", metaphone.encode("ODSIAN"));
        assertEquals("PLXN", metaphone.encode("PULSION"));
        assertEquals("RX", metaphone.encode("RETCH"));
        assertEquals("WX", metaphone.encode("WATCH"));
        assertEquals("OX", metaphone.encode("OTIA"));
        assertEquals("PRXN", metaphone.encode("PORTION"));
        assertEquals("SKTL", metaphone.encode("SCHEDULE"));
        assertEquals("SKMT", metaphone.encode("SCHEMATIC"));
        assertEquals("TSKR", metaphone.encode("DISCHARGE"));
        assertEquals("EX", metaphone.encode("ECHO"));
        assertEquals("TX", metaphone.encode("TEACH"));
        assertEquals("XR", metaphone.encode("CHERI"));
        assertEquals("XP", metaphone.encode("CHIP"));
        assertEquals("XRST", metaphone.encode("CHRIST"));
        assertEquals("X", metaphone.encode("CIAO"));
        assertEquals("ST", metaphone.encode("CITY"));
        assertEquals("KT", metaphone.encode("CAT"));
        assertEquals("TJ", metaphone.encode("DODGY"));
        assertEquals("TJ", metaphone.encode("DODGE"));
        assertEquals("AJMT", metaphone.encode("ADGIEMTI"));
        assertEquals("KM", metaphone.encode("COMB"));
        assertEquals("TM", metaphone.encode("TOMB"));
        assertEquals("WM", metaphone.encode("WOMB"));
        assertEquals("XP", metaphone.encode("CIAPO"));
    }

    @Test
    public void testMaxCodeLen() {
        Metaphone m = new Metaphone();
        assertEquals(4, m.getMaxCodeLen());
        m.setMaxCodeLen(6);
        assertEquals("AKSKSK", m.encode("AXEAXEAXE"));
        m.setMaxCodeLen(5);
        assertEquals("XRKTR", m.encode("CHARACTER"));
    }

    @Test
    public void testNoOutput() {
        assertNull(metaphone.encode(null));
        assertNull(metaphone.encode(""));
        assertNull(metaphone.encode("WHY"));
        assertEquals("A", metaphone.encode("a"));
    }

    @Test
    public void testEqualGroups() {
        assertAllEqual("Case", "case", "CASE", "caSe");
        assertAllEqual("quick", "cookie");
        assertAllEqual("Lawrence", "Lorenza");
        assertAllEqual("Gary", "Cahra", "Cara", "Carey", "Cari", "Caria", "Carie", "Caro", "Carree", "Carri", "Carrie", "Carry", "Cary",
            "Cora", "Corey", "Cori", "Corie", "Correy", "Corri", "Corrie", "Corry", "Cory", "Gray", "Kara", "Kare", "Karee", "Kari",
            "Karia", "Karie", "Karrah", "Karrie", "Karry", "Kary", "Keri", "Kerri", "Kerrie", "Kerry", "Kira", "Kiri", "Kora", "Kore",
            "Kori", "Korie", "Korrie", "Korry");
        assertAllEqual("Aero", "Eure");
        assertAllEqual("Albert", "Ailbert", "Alberik", "Albert", "Alberto", "Albrecht");
        assertAllEqual("John", "Gena", "Gene", "Genia", "Genna", "Genni", "Gennie", "Genny", "Giana", "Gianna", "Gina", "Ginni",
            "Ginnie", "Ginny", "Jaine", "Jan", "Jana", "Jane", "Janey", "Jania", "Janie", "Janna", "Jany", "Jayne", "Jean", "Jeana",
            "Jeane", "Jeanie", "Jeanna", "Jeanne", "Jeannie", "Jen", "Jena", "Jeni", "Jenn", "Jenna", "Jennee", "Jenni", "Jennie",
            "Jenny", "Jinny", "Jo Ann", "Jo-Ann", "Jo-Anne", "Joan", "Joana", "Joane", "Joanie", "Joann", "Joanna", "Joanne", "Joeann",
            "Johna", "Johnna", "Joni", "Jonie", "Juana", "June", "Junia", "Junie");
        assertAllEqual("Knight", "Hynda", "Nada", "Nadia", "Nady", "Nat", "Nata", "Natty", "Neda", "Nedda", "Nedi", "Netta", "Netti",
            "Nettie", "Netty", "Nita", "Nydia");
        assertAllEqual("Mary", "Mair", "Maire", "Mara", "Mareah", "Mari", "Maria", "Marie", "Mary", "Maura", "Maure", "Meara", "Merrie",
            "Merry", "Mira", "Moira", "Mora", "Moria", "Moyra", "Muire", "Myra", "Myrah");
        assertAllEqual("Paris", "Pearcy", "Perris", "Piercy", "Pierz", "Pryse");
        assertAllEqual("Peter", "Peadar", "Peder", "Pedro", "Peter", "Petr", "Peyter", "Pieter", "Pietro", "Piotr");
        assertAllEqual("Ray", "Ray", "Rey", "Roi", "Roy", "Ruy");
        assertAllEqual("Susan", "Siusan", "Sosanna", "Susan", "Susana", "Susann", "Susanna", "Susannah", "Susanne", "Suzann",
            "Suzanna", "Suzanne", "Zuzana");
        assertAllEqual("White", "Wade", "Wait", "Waite", "Wat", "Whit", "Wiatt", "Wit", "Wittie", "Witty", "Wood", "Woodie", "Woody");
        assertAllEqual("Wright", "Rota", "Rudd", "Ryde");
        assertAllEqual("Xalan", "Celene", "Celina", "Celine", "Selena", "Selene", "Selina", "Seline", "Suellen", "Xylina");
    }
}
