package com.naqqa.elasticsearch.analysis.phonetic;

import static com.naqqa.elasticsearch.test.Assert.assertEquals;
import static com.naqqa.elasticsearch.test.Assert.assertThrows;
import static com.naqqa.elasticsearch.test.Assert.assertTrue;

import com.naqqa.elasticsearch.test.Test;

public class PhoneticEncodersTest {

    public PhoneticEncodersTest() {
    }

    @Test
    public void testNames() {
        assertTrue(PhoneticEncoders.create("metaphone", null) instanceof Metaphone);
        assertTrue(PhoneticEncoders.create("double_metaphone", null) instanceof DoubleMetaphone);
        assertTrue(PhoneticEncoders.create("doubleMetaphone", null) instanceof DoubleMetaphone);
        assertTrue(PhoneticEncoders.create("soundex", null) instanceof Soundex);
        assertTrue(PhoneticEncoders.create("refined_soundex", null) instanceof RefinedSoundex);
        assertTrue(PhoneticEncoders.create("refinedsoundex", null) instanceof RefinedSoundex);
        assertTrue(PhoneticEncoders.create("refinedSoundex", null) instanceof RefinedSoundex);
        assertTrue(PhoneticEncoders.create("caverphone1", null) instanceof Caverphone1);
        assertTrue(PhoneticEncoders.create("caverphone2", null) instanceof Caverphone2);
        assertTrue(PhoneticEncoders.create("caverphone", null) instanceof Caverphone2);
        assertTrue(PhoneticEncoders.create("cologne", null) instanceof ColognePhonetic);
        assertTrue(PhoneticEncoders.create("koelnerphonetik", null) instanceof KoelnerPhonetik);
        assertTrue(PhoneticEncoders.create("haasephonetik", null) instanceof HaasePhonetik);
        assertTrue(PhoneticEncoders.create("nysiis", null) instanceof Nysiis);
        assertTrue(PhoneticEncoders.create("SOUNDEX", null) instanceof Soundex);
    }

    @Test
    public void testMaxCodeLen() {
        DoubleMetaphone dm = (DoubleMetaphone) PhoneticEncoders.create("double_metaphone", 6);
        assertEquals(6, dm.getMaxCodeLen());
        assertEquals("SPRKLF", dm.encode("supercalifragilisticexpialidocious"));
        DoubleMetaphone def = (DoubleMetaphone) PhoneticEncoders.create("double_metaphone", null);
        assertEquals(4, def.getMaxCodeLen());
        Metaphone m = (Metaphone) PhoneticEncoders.create("metaphone", 6);
        assertEquals(4, m.getMaxCodeLen());
    }

    @Test
    public void testEncodes() {
        assertEquals("R163", PhoneticEncoders.create("soundex", null).encode("Robert"));
        assertEquals("BLKS", PhoneticEncoders.create("metaphone", null).encode("Bloggs"));
        assertEquals("657", PhoneticEncoders.create("cologne", null).encode("Müller"));
    }

    @Test
    public void testUnknown() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> PhoneticEncoders.create("foo", null));
        assertEquals("unknown encoder [foo] for phonetic token filter", e.getMessage());
    }
}
