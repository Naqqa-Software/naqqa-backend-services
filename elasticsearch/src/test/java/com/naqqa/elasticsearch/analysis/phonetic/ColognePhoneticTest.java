package com.naqqa.elasticsearch.analysis.phonetic;

import static com.naqqa.elasticsearch.test.Assert.assertEquals;
import static com.naqqa.elasticsearch.test.Assert.assertNull;
import static com.naqqa.elasticsearch.test.Assert.assertTrue;

import com.naqqa.elasticsearch.test.Test;

public class ColognePhoneticTest {

    private final ColognePhonetic encoder = new ColognePhonetic();

    public ColognePhoneticTest() {
    }

    private void check(String expected, String... inputs) {
        for (String input : inputs) {
            assertEquals(expected, encoder.encode(input), input);
        }
    }

    @Test
    public void testBasic() {
        check("01", "Aabjoe");
        check("0856", "Aaclan");
        check("04567", "Aychlmajr");
    }

    @Test
    public void testEdgeCases() {
        check("0", "a", "e", "i", "o", "u", "ä", "ö", "ü", "aa", "ha", "aha");
        check("8", "ß");
        check("1", "b", "p");
        check("3", "ph", "f", "v", "w");
        check("4", "g", "k", "q");
        check("48", "x", "cx");
        check("048", "ax");
        check("5", "l");
        check("45", "cl");
        check("085", "acl");
        check("6", "mn", "{mn}");
        check("7", "r");
        assertNull(encoder.encode("h"));
        assertNull(encoder.encode(null));
        assertNull(encoder.encode(""));
    }

    @Test
    public void testExamples() {
        check("657", "mÜller", "müller");
        check("862", "schmidt");
        check("8627", "schneider");
        check("387", "fischer");
        check("317", "weber");
        check("3467", "wagner");
        check("147", "becker");
        check("036", "hoffmann");
        check("837", "schÄfer", "schäfer");
        check("17863", "Breschnew");
        check("3412", "Wikipedia");
        check("127", "peter");
        check("376", "pharma");
        check("64645214", "mönchengladbach");
        check("28", "deutsch", "deutz");
        check("06174", "hamburg");
        check("0637", "hannover");
        check("478256", "christstollen");
        check("48621", "Xanthippe");
        check("8478", "Zacharias");
        check("0581", "Holzbau");
        check("68", "matsch", "matz");
        check("071862", "Arbeitsamt");
        check("0172", "Eberhard", "Eberhardt");
        check("858", "Celsius");
        check("08", "Ace");
        check("84", "shch");
        check("484", "xch");
        check("021", "heithabu");
        check("174845214", "bergisch-gladbach");
        check("65752682", "Müller-Lüdenscheidt");
    }

    @Test
    public void testVariations() {
        check("28282", "Test test", "Testtest", "Test-test", "TesT#Test", "TesT?test");
        check("65", "mella", "milah", "moulla", "mellah", "muehle", "mule");
        check("67", "Meier", "Maier", "Mair", "Meyer", "Meyr", "Mejer", "Major");
    }

    @Test
    public void testIsEncodeEqual() {
        String[][] pairs = {{"Muller", "Müller"}, {"Meyer", "Mayr"}, {"house", "house"}, {"House", "house"}, {"Haus", "house"},
            {"ganz", "Gans"}, {"ganz", "Gänse"}, {"Miyagi", "Miyako"}};
        for (String[] pair : pairs) {
            assertTrue(encoder.isEncodeEqual(pair[0], pair[1]), pair[0] + " " + pair[1]);
        }
    }
}
