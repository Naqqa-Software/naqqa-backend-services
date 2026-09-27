package com.naqqa.elasticsearch.analysis.folding;

import static com.naqqa.elasticsearch.test.Assert.assertEquals;
import static com.naqqa.elasticsearch.test.Assert.assertFalse;
import static com.naqqa.elasticsearch.test.Assert.assertNull;
import static com.naqqa.elasticsearch.test.Assert.assertSame;
import static com.naqqa.elasticsearch.test.Assert.assertTrue;

import com.naqqa.elasticsearch.test.Test;

public class AsciiFolderTest {

    public AsciiFolderTest() {
    }

    @Test
    public void foldsAccentedLatin() {
        assertEquals("AAAAAA", AsciiFolder.fold("ÀÁÂÃÄÅ"));
        assertEquals("aaaaaa", AsciiFolder.fold("àáâãäå"));
        assertEquals("Cafe creme brulee", AsciiFolder.fold("Café crème brûlée"));
        assertEquals("Zolc gesla jazn", AsciiFolder.fold("Żółć gęślą jaźń"));
        assertEquals("Strasse", AsciiFolder.fold("Straße"));
        assertEquals("THorn", AsciiFolder.fold("Þorn"));
        assertEquals("Nguyen Van Thuc", AsciiFolder.fold("Nguyễn Văn Thức"));
    }

    @Test
    public void foldsLigatures() {
        assertEquals("AE", AsciiFolder.fold("Æ"));
        assertEquals("ae", AsciiFolder.fold("æ"));
        assertEquals("OE", AsciiFolder.fold("Œ"));
        assertEquals("ff", AsciiFolder.fold("ﬀ"));
        assertEquals("ffi", AsciiFolder.fold("ﬃ"));
        assertEquals("st", AsciiFolder.fold("ﬆ"));
        assertEquals("IJ", AsciiFolder.fold("Ĳ"));
    }

    @Test
    public void foldsEnclosedAlphanumerics() {
        assertEquals("(1)", AsciiFolder.fold("⑴"));
        assertEquals("1", AsciiFolder.fold("①"));
        assertEquals("20.", AsciiFolder.fold("⒛"));
        assertEquals("(a)", AsciiFolder.fold("⒜"));
        assertEquals("A", AsciiFolder.fold("Ⓐ"));
        assertEquals("2", AsciiFolder.fold("²"));
    }

    @Test
    public void foldsFullwidth() {
        assertEquals("ABC xyz 123!", AsciiFolder.fold("ＡＢＣ ｘｙｚ １２３！"));
        assertEquals("~", AsciiFolder.fold("～"));
        assertEquals("\\", AsciiFolder.fold("＼"));
    }

    @Test
    public void foldsPunctuation() {
        assertEquals("\"quoted\"", AsciiFolder.fold("“quoted”"));
        assertEquals("\"x\"", AsciiFolder.fold("«x»"));
        assertEquals("'s", AsciiFolder.fold("’s"));
        assertEquals("a-b", AsciiFolder.fold("a–b"));
        assertEquals("??", AsciiFolder.fold("⁇"));
        assertEquals("?!", AsciiFolder.fold("⁈"));
    }

    @Test
    public void leavesUnmappedCharacters() {
        assertEquals("日本", AsciiFolder.fold("日本"));
        assertEquals("мир", AsciiFolder.fold("мир"));
        String ascii = "plain ascii";
        assertSame(ascii, AsciiFolder.fold(ascii));
        assertNull(AsciiFolder.fold(null));
    }

    @Test
    public void lowLevelApi() {
        char[] in = "xxÆßyy".toCharArray();
        char[] out = new char[4 * 2 + 3];
        out[0] = '#';
        int end = AsciiFolder.foldToASCII(in, 2, out, 1, 2);
        assertEquals(5, end);
        assertEquals("#AEss", new String(out, 0, end));
        assertTrue(AsciiFolder.needsFolding(in, 0, in.length));
        assertFalse(AsciiFolder.needsFolding(in, 0, 2));
        assertFalse(AsciiFolder.needsFolding(in, 4, 2));
    }

    @Test
    public void tableSize() {
        int mapped = 0;
        int maxLen = 0;
        for (int c = 0x80; c <= 0xFFFF; c++) {
            String f = AsciiFolder.foldingOf((char) c);
            if (f != null) {
                mapped++;
                maxLen = Math.max(maxLen, f.length());
                for (int i = 0; i < f.length(); i++) {
                    assertTrue(f.charAt(i) < 0x80, "non-ascii output for " + Integer.toHexString(c));
                }
            }
        }
        assertEquals(1242, mapped);
        assertEquals(4, maxLen);
    }
}
