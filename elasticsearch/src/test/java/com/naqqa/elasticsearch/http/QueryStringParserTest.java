package com.naqqa.elasticsearch.http;

import com.naqqa.elasticsearch.test.Assert;

import java.util.List;
import java.util.Map;

public final class QueryStringParserTest {

    @com.naqqa.elasticsearch.test.Test
    public void parsesRepeatedAndEncodedValues() {
        Map<String, List<String>> parsed = QueryStringParser.parse("a=1&a=2&b=hello%20world&c");
        Assert.assertEquals(List.of("1", "2"), parsed.get("a"));
        Assert.assertEquals(List.of("hello world"), parsed.get("b"));
        Assert.assertEquals(List.of(""), parsed.get("c"));
    }

    @com.naqqa.elasticsearch.test.Test
    public void splitCommaTrimsAndDropsEmpty() {
        Assert.assertEquals(List.of("a", "b", "c"), QueryStringParser.splitComma(" a, b ,,c"));
        Assert.assertEquals(List.of(), QueryStringParser.splitComma(""));
        Assert.assertEquals(List.of(), QueryStringParser.splitComma(null));
    }

    @com.naqqa.elasticsearch.test.Test
    public void urlCodecDecodesPercentAndPlus() {
        Assert.assertEquals("a b", UrlCodec.decode("a+b", true));
        Assert.assertEquals("a+b", UrlCodec.decode("a+b", false));
        Assert.assertEquals("a/b", UrlCodec.decode("a%2Fb", false));
        Assert.assertEquals("café", UrlCodec.decode("caf%C3%A9", false));
    }
}
