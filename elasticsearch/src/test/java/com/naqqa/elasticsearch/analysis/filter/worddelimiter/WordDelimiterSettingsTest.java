package com.naqqa.elasticsearch.analysis.filter.worddelimiter;

import com.naqqa.elasticsearch.test.Test;

import java.util.List;
import java.util.Map;

import static com.naqqa.elasticsearch.analysis.filter.worddelimiter.WordDelimiterGraphFilter.*;
import static com.naqqa.elasticsearch.test.Assert.assertEquals;
import static com.naqqa.elasticsearch.test.Assert.assertFalse;
import static com.naqqa.elasticsearch.test.Assert.assertNull;
import static com.naqqa.elasticsearch.test.Assert.assertSame;
import static com.naqqa.elasticsearch.test.Assert.assertThrows;
import static com.naqqa.elasticsearch.test.Assert.assertTrue;

public class WordDelimiterSettingsTest {

    @Test
    public void defaultFlags() {
        int expected = GENERATE_WORD_PARTS | GENERATE_NUMBER_PARTS | SPLIT_ON_CASE_CHANGE | SPLIT_ON_NUMERICS | STEM_ENGLISH_POSSESSIVE;
        assertEquals(expected, WordDelimiterSettings.flags(Map.of()));
        assertEquals(expected, WordDelimiterSettings.flags(null));
    }

    @Test
    public void explicitFlags() {
        int flags = WordDelimiterSettings.flags(Map.of(
            "generate_word_parts", "false",
            "generate_number_parts", false,
            "catenate_words", true,
            "catenate_numbers", "true",
            "catenate_all", true,
            "split_on_case_change", false,
            "preserve_original", "true",
            "split_on_numerics", "false",
            "stem_english_possessive", false,
            "ignore_keywords", true));
        assertEquals(CATENATE_WORDS | CATENATE_NUMBERS | CATENATE_ALL | PRESERVE_ORIGINAL | IGNORE_KEYWORDS, flags);
    }

    @Test
    public void invalidBoolean() {
        assertThrows(IllegalArgumentException.class, () -> WordDelimiterSettings.flags(Map.of("catenate_words", "yes")));
    }

    @Test
    public void adjustOffsetsDefault() {
        assertTrue(WordDelimiterSettings.adjustOffsets(Map.of()));
        assertFalse(WordDelimiterSettings.adjustOffsets(Map.of("adjust_offsets", "false")));
    }

    @Test
    public void typeTable() {
        byte[] table = WordDelimiterSettings.parseTypeTable(List.of("# => ALPHA", "\\u200D => ALPHANUM", "$ => DIGIT", "a => SUBWORD_DELIM",
            "B => LOWER", "c => UPPER"));
        assertEquals(0x200D + 1, table.length);
        assertEquals((byte) WordDelimiterIterator.ALPHA, table['#']);
        assertEquals((byte) WordDelimiterIterator.ALPHANUM, table[0x200D]);
        assertEquals((byte) WordDelimiterIterator.DIGIT, table['$']);
        assertEquals((byte) WordDelimiterIterator.SUBWORD_DELIM, table['a']);
        assertEquals((byte) WordDelimiterIterator.LOWER, table['B']);
        assertEquals((byte) WordDelimiterIterator.UPPER, table['c']);
        assertEquals((byte) WordDelimiterIterator.LOWER, table['z']);
        assertEquals((byte) WordDelimiterIterator.SUBWORD_DELIM, table['-']);
        assertEquals((byte) WordDelimiterIterator.DIGIT, table['7']);
        byte[] small = WordDelimiterSettings.parseTypeTable(List.of("- => ALPHA"));
        assertEquals(256, small.length);
        assertSame(WordDelimiterIterator.DEFAULT_WORD_DELIM_TABLE, WordDelimiterSettings.parseTypeTable(null));
    }

    @Test
    public void invalidTypeRules() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> WordDelimiterSettings.parseTypeTable(List.of("# ALPHA")));
        assertEquals("Invalid Mapping Rule : [# ALPHA]", e.getMessage());
        e = assertThrows(IllegalArgumentException.class, () -> WordDelimiterSettings.parseTypeTable(List.of("ab => ALPHA")));
        assertEquals("Invalid Mapping Rule : [ab => ALPHA]. Only a single character is allowed.", e.getMessage());
        e = assertThrows(IllegalArgumentException.class, () -> WordDelimiterSettings.parseTypeTable(List.of("a => FOO")));
        assertEquals("Invalid Mapping Rule : [a => FOO]. Illegal type.", e.getMessage());
        assertThrows(IllegalArgumentException.class, () -> WordDelimiterSettings.parseTypeTable(List.of("\\u20 => ALPHA")));
    }

    @Test
    public void protectedWords() {
        assertNull(WordDelimiterSettings.protectedWords(null));
        assertTrue(WordDelimiterSettings.protectedWords(List.of("a", "b")).contains("b"));
    }
}
