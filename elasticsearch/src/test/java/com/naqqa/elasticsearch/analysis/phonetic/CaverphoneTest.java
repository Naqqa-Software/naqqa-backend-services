package com.naqqa.elasticsearch.analysis.phonetic;

import static com.naqqa.elasticsearch.test.Assert.assertEquals;
import static com.naqqa.elasticsearch.test.Assert.assertFalse;
import static com.naqqa.elasticsearch.test.Assert.assertNull;
import static com.naqqa.elasticsearch.test.Assert.assertTrue;

import com.naqqa.elasticsearch.test.Test;

public class CaverphoneTest {

    private final Caverphone1 caverphone1 = new Caverphone1();
    private final Caverphone2 caverphone2 = new Caverphone2();

    public CaverphoneTest() {
    }

    private static void check(PhoneticEncoder encoder, String expected, String... inputs) {
        for (String input : inputs) {
            assertEquals(expected, encoder.encode(input), input);
        }
    }

    @Test
    public void testCaverphone1CommonCode() {
        check(caverphone1, "AT1111", "add", "aid", "at", "art", "eat", "earth", "head", "hit", "hot", "hold", "hard", "heart", "it",
            "out", "old");
    }

    @Test
    public void testCaverphone1Examples() {
        check(caverphone1, "M11111", "mb");
        check(caverphone1, "MPM111", "mbmb");
        check(caverphone1, "TFT111", "David");
        check(caverphone1, "WTL111", "Whittle");
        check(caverphone1, "L11111", "Lee");
        check(caverphone1, "TMPSN1", "Thompson");
        assertFalse(caverphone1.isEncodeEqual("Peter", "Stevenson"));
        assertTrue(caverphone1.isEncodeEqual("Peter", "Peady"));
    }

    @Test
    public void testCaverphone2CommonCode() {
        check(caverphone2, "AT11111111", "add", "aid", "at", "art", "eat", "earth", "head", "hit", "hot", "hold", "hard", "heart",
            "it", "out", "old");
    }

    @Test
    public void testCaverphone2Klns() {
        check(caverphone2, "KLN1111111", "Cailean", "Calan", "Calen", "Callahan", "Callan", "Callean", "Carleen", "Carlen", "Carlene",
            "Carlin", "Carline", "Carlyn", "Carlynn", "Carlynne", "Charlean", "Charleen", "Charlene", "Charline", "Cherlyn", "Chirlin",
            "Clein", "Cleon", "Cline", "Cohleen", "Colan", "Coleen", "Colene", "Colin", "Colleen", "Collen", "Collin", "Colline",
            "Colon", "Cullan", "Cullen", "Cullin", "Gaelan", "Galan", "Galen", "Garlan", "Garlen", "Gaulin", "Gayleen", "Gaylene",
            "Giliane", "Gillan", "Gillian", "Glen", "Glenn", "Glyn", "Glynn", "Gollin", "Gorlin", "Kalin", "Karlan", "Karleen", "Karlen",
            "Karlene", "Karlin", "Karlyn", "Kaylyn", "Keelin", "Kellen", "Kellene", "Kellyann", "Kellyn", "Khalin", "Kilan", "Kilian",
            "Killen", "Killian", "Killion", "Klein", "Kleon", "Kline", "Koerlin", "Kylen", "Kylynn", "Quillan", "Quillon", "Qulllon",
            "Xylon");
    }

    @Test
    public void testCaverphone2Examples() {
        check(caverphone2, "TN11111111", "Dan", "Dane", "Dyun");
        check(caverphone2, "TTA1111111", "Tutto", "Tedder");
        check(caverphone2, "RTA1111111", "rather", "ready", "writer");
        check(caverphone2, "SSA1111111", "social");
        check(caverphone2, "APA1111111", "able", "appear");
        check(caverphone2, "M111111111", "mb");
        check(caverphone2, "MPM1111111", "mbmb");
        check(caverphone2, "STFNSN1111", "Stevenson");
        check(caverphone2, "PTA1111111", "Peter");
        check(caverphone2, "KLN1111111", "Karleen");
        assertFalse(caverphone2.isEncodeEqual("Peter", "Stevenson"));
        assertTrue(caverphone2.isEncodeEqual("Peter", "Peady"));
    }

    @Test
    public void testEmptyAndNull() {
        assertNull(caverphone1.encode(null));
        assertNull(caverphone2.encode(null));
        assertEquals("111111", caverphone1.encode(""));
        assertEquals("1111111111", caverphone2.encode(""));
        assertEquals("1111111111", caverphone2.encode("123"));
    }
}
