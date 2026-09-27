package com.naqqa.elasticsearch.analysis.phonetic;

import static com.naqqa.elasticsearch.test.Assert.assertEquals;
import static com.naqqa.elasticsearch.test.Assert.assertNull;

import com.naqqa.elasticsearch.test.Test;

public class RefinedSoundexTest {

    private final RefinedSoundex encoder = new RefinedSoundex();

    public RefinedSoundexTest() {
    }

    @Test
    public void testEncode() {
        assertEquals("T6036084", encoder.encode("testing"));
        assertEquals("T6036084", encoder.encode("TESTING"));
        assertEquals("T60", encoder.encode("The"));
        assertEquals("Q503", encoder.encode("quick"));
        assertEquals("B1908", encoder.encode("brown"));
        assertEquals("F205", encoder.encode("fox"));
        assertEquals("J408106", encoder.encode("jumped"));
        assertEquals("O0209", encoder.encode("over"));
        assertEquals("T60", encoder.encode("the"));
        assertEquals("L7050", encoder.encode("lazy"));
        assertEquals("D6043", encoder.encode("dogs"));
    }

    @Test
    public void testDifference() {
        assertEquals(6, encoder.difference("Smith", "Smythe"));
        assertEquals(3, encoder.difference("Ann", "Andrew"));
        assertEquals(1, encoder.difference("Margaret", "Andrew"));
        assertEquals(1, encoder.difference("Janet", "Margaret"));
        assertEquals(5, encoder.difference("Green", "Greene"));
        assertEquals(1, encoder.difference("Blotchet-Halls", "Greene"));
        assertEquals(8, encoder.difference("Smithers", "Smythers"));
        assertEquals(5, encoder.difference("Anothers", "Brothers"));
        assertEquals(0, encoder.difference(null, null));
        assertEquals(0, encoder.difference(" ", " "));
    }

    @Test
    public void testNonLetterMapping() {
        assertEquals(0, (int) encoder.getMappingCode('#'));
    }

    @Test
    public void testInvalidCharacters() {
        char[] invalid = new char[256];
        for (int i = 0; i < invalid.length; i++) {
            invalid[i] = (char) i;
        }
        assertEquals("A0136024043780159360205050136024043780159360205053", encoder.encode(new String(invalid)));
    }

    @Test
    public void testNoOutput() {
        assertNull(encoder.encode(null));
        assertNull(encoder.encode(""));
        assertNull(encoder.encode("42"));
    }
}
