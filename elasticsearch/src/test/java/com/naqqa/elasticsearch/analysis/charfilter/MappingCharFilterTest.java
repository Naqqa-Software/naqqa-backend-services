package com.naqqa.elasticsearch.analysis.charfilter;

import com.naqqa.elasticsearch.analysis.FilteredText;
import com.naqqa.elasticsearch.analysis.OffsetCorrector;
import com.naqqa.elasticsearch.test.Assert;
import com.naqqa.elasticsearch.test.Test;

import java.util.LinkedHashMap;
import java.util.Map;

public final class MappingCharFilterTest {

    private static String apply(MappingCharFilter f, String in) {
        FilteredText out = new FilteredText();
        out.reset(OffsetCorrector.IDENTITY);
        f.filter(in, out);
        return out.text().toString();
    }

    @Test
    public void longestMatchWinsAndOffsetsAreCorrected() {
        MappingCharFilter f = new MappingCharFilter(Map.of("ab", "X", "abc", "Y", "c", "Z"));
        Assert.assertEquals("YZ", apply(f, "abcc"));
        FilteredText out = new FilteredText();
        out.reset(OffsetCorrector.IDENTITY);
        f.filter("abcd", out);
        Assert.assertEquals("Yd", out.text().toString());
        Assert.assertEquals(4, out.correctOffset(2));
    }

    @Test
    public void unmatchedTextAndKeyLongerThanInputAreHandled() {
        MappingCharFilter f = new MappingCharFilter(Map.of("hello", "H", "a", "b"));
        Assert.assertEquals("xhellb", apply(f, "xhella"));
        Assert.assertEquals("hell", apply(f, "hell"));
    }

    @Test
    public void manyRulesOnLargeInputRunsInLinearTime() {
        Map<String, String> rules = new LinkedHashMap<>();
        for (int i = 0; i < 5_000; i++) {
            rules.put("k" + i + "z", "v");
        }
        rules.put("a", "b");
        MappingCharFilter f = new MappingCharFilter(rules);
        String in = "a".repeat(2_000_000);
        long start = System.nanoTime();
        String out = apply(f, in);
        long millis = (System.nanoTime() - start) / 1_000_000;
        Assert.assertEquals(in.length(), out.length());
        Assert.assertTrue(millis < 5_000, "took " + millis + " ms");
    }
}
