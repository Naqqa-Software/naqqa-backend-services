package com.naqqa.elasticsearch.analysis.phonetic;

import static com.naqqa.elasticsearch.test.Assert.assertEquals;
import static com.naqqa.elasticsearch.test.Assert.assertNull;

import com.naqqa.elasticsearch.test.Test;

public class KoelnerPhonetikTest {

    private final KoelnerPhonetik koelner = new KoelnerPhonetik();
    private final HaasePhonetik haase = new HaasePhonetik();

    public KoelnerPhonetikTest() {
    }

    @Test
    public void testKoelnerSingleWords() {
        assertEquals("657", koelner.encode("Müller"));
        assertEquals("67", koelner.encode("Meyer"));
        assertEquals("17863", koelner.encode("Breschnew"));
        assertEquals("127", koelner.encode("Peter"));
        assertEquals("862", koelner.encode("Schmidt"));
    }

    @Test
    public void testKoelnerVariations() {
        assertEquals("07172_07372", koelner.encode("HERBERT"));
        assertEquals("07172", koelner.encode("Herbert"));
    }

    @Test
    public void testKoelnerMultipleParts() {
        assertEquals("068127_068_127", koelner.encode("Hans Peter"));
        assertEquals("068127", new KoelnerPhonetik(true).encode("Hans Peter"));
    }

    @Test
    public void testHaase() {
        assertEquals("96", haase.encode("Anna"));
        assertEquals("96_967", haase.encode("ANNA"));
        assertEquals("4826_826", haase.encode("CHRISTIAN"));
        assertEquals("657", haase.encode("Müller"));
        assertEquals("9172", haase.encode("Eberhard"));
    }

    @Test
    public void testNoOutput() {
        assertNull(koelner.encode(null));
        assertNull(koelner.encode(""));
        assertNull(haase.encode(null));
        assertNull(haase.encode("h"));
    }

    @Test
    public void testRelativeValue() {
        assertEquals(1.0, koelner.getRelativeValue("Meyer", "Mayr"), 0.0);
        assertEquals(0.0, koelner.getRelativeValue("Meyer", "Schmidt"), 0.0);
    }
}
