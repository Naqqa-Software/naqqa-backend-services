package com.naqqa.elasticsearch.analysis.hyphenation;

import static com.naqqa.elasticsearch.test.Assert.assertEquals;
import static com.naqqa.elasticsearch.test.Assert.assertNotNull;
import static com.naqqa.elasticsearch.test.Assert.assertNull;

import com.naqqa.elasticsearch.test.Test;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class HyphenationTreeTest {

    private static final String XML = """
        <?xml version="1.0" encoding="utf-8"?>
        <!DOCTYPE hyphenation-info SYSTEM "hyphenation.dtd">
        <hyphenation-info>
        <hyphen-char value="-"/>
        <hyphen-min before="2" after="2"/>
        <classes>
        aA bB cC dD eE fF gG hH iI jJ kK lL mM nN oO pP qQ rR sS tT uU vV wW xX yY zZ
        </classes>
        <exceptions>
        as-so-ciate pro-ject
        ta<hyphen pre="k" no="c" post="k"/>ing
        </exceptions>
        <patterns>
        hy3ph he2n hena4 hen5at 1na n2at 1tio 2io
        .ta5ble 4ble
        </patterns>
        </hyphenation-info>
        """;

    public HyphenationTreeTest() {
    }

    private static HyphenationTree tree() throws IOException {
        return HyphenationTree.load(new ByteArrayInputStream(XML.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    public void patternsFromXml() throws IOException {
        HyphenationTree tree = tree();
        Hyphenation h = tree.hyphenate("hyphenation", 2, 2);
        assertNotNull(h);
        assertEquals(new int[] {0, 2, 6, 11}, h.getHyphenationPoints());
        assertEquals(new int[] {0, 2, 6, 11}, tree.hyphenate("Hyphenation", 2, 2).getHyphenationPoints());
        assertEquals(new int[] {0, 2, 5}, tree.hyphenate("table", 1, 1).getHyphenationPoints());
        assertEquals("00300", tree.findPattern("hyph"));
    }

    @Test
    public void exceptionsFromXml() throws IOException {
        HyphenationTree tree = tree();
        assertEquals(new int[] {0, 2, 4, 9}, tree.hyphenate("associate", 1, 1).getHyphenationPoints());
        assertEquals(new int[] {0, 3, 7}, tree.hyphenate("project", 1, 1).getHyphenationPoints());
        assertEquals(new int[] {0, 2, 6}, tree.hyphenate("tacing", 1, 1).getHyphenationPoints());
    }

    @Test
    public void remainAndPushLimits() throws IOException {
        HyphenationTree tree = tree();
        assertEquals(new int[] {0, 6, 11}, tree.hyphenate("hyphenation", 3, 2).getHyphenationPoints());
        assertNull(tree.hyphenate("hyphenation", 7, 5));
        assertNull(tree.hyphenate("hy", 2, 2));
        assertNull(tree.hyphenate("zzzz", 1, 1));
    }

    @Test
    public void leadingNonLettersAreSkipped() throws IOException {
        HyphenationTree tree = tree();
        assertEquals(new int[] {0, 3, 7, 11}, tree.hyphenate("\"hyphenation", 2, 2).getHyphenationPoints());
        assertNull(tree.hyphenate("hyphen1ation", 2, 2));
    }

    @Test
    public void programmaticConstruction() {
        HyphenationTree tree = new HyphenationTree();
        tree.addClass("aA");
        tree.addClass("hH");
        tree.addClass("yY");
        tree.addClass("pP");
        tree.addClass("eE");
        tree.addClass("nN");
        tree.addClass("tT");
        tree.addClass("iI");
        tree.addClass("oO");
        for (String p : "hy3ph he2n hena4 hen5at 1na n2at 1tio 2io".split(" ")) {
            tree.addPattern(p);
        }
        assertEquals(new int[] {0, 2, 6, 11}, tree.hyphenate("HYPHENATION", 2, 2).getHyphenationPoints());
        tree.addException("hyphenation", List.of("hyp", new Hyphen("-"), "hen", new Hyphen("-"), "ation"));
        assertEquals(new int[] {0, 3, 6, 11}, tree.hyphenate("hyphenation", 2, 2).getHyphenationPoints());
    }

    @Test
    public void loadFromPath() throws IOException {
        Path file = Files.createTempFile("hyph", ".xml");
        try {
            Files.writeString(file, XML, StandardCharsets.UTF_8);
            HyphenationTree tree = HyphenationTree.load(file);
            assertEquals(new int[] {0, 2, 6, 11}, tree.hyphenate("hyphenation", 2, 2).getHyphenationPoints());
        } finally {
            Files.deleteIfExists(file);
        }
    }
}
