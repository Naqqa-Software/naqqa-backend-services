package com.naqqa.elasticsearch.analysis.charfilter.html;

import com.naqqa.elasticsearch.test.Assert;
import com.naqqa.elasticsearch.test.Test;

public final class HtmlStripCharFilterTest {

    @Test
    public void stripsTagsAndDecodesEntities() {
        HtmlStripCharFilter filter = new HtmlStripCharFilter();
        String out = filter.apply("<p>Hello &amp; welcome</p>");
        Assert.assertEquals("Hello & welcome", out);
    }

    @Test
    public void removesScriptContentByDefault() {
        HtmlStripCharFilter filter = new HtmlStripCharFilter();
        String out = filter.apply("a<script>var x=1;</script>b");
        Assert.assertEquals("ab", out);
    }

    @Test
    public void offsetsCorrectAfterEntityDecoding() {
        com.naqqa.elasticsearch.analysis.FilteredText out = new com.naqqa.elasticsearch.analysis.FilteredText();
        out.reset(com.naqqa.elasticsearch.analysis.OffsetCorrector.IDENTITY);
        new HtmlStripCharFilter().filter("a&amp;b", out);
        Assert.assertEquals("a&b", out.text().toString());
        Assert.assertEquals(6, out.correctOffset(2));
    }
}
