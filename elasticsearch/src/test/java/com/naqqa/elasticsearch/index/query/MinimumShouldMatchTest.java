package com.naqqa.elasticsearch.index.query;

import com.naqqa.elasticsearch.test.Assert;
import com.naqqa.elasticsearch.test.Test;

public final class MinimumShouldMatchTest {

    @Test
    public void fixedValue() {
        Assert.assertEquals(3, MinimumShouldMatch.parse("3").resolve(5));
    }

    @Test
    public void negativeValue() {
        Assert.assertEquals(2, MinimumShouldMatch.parse("-2").resolve(4));
    }

    @Test
    public void percentage() {
        Assert.assertEquals(3, MinimumShouldMatch.parse("75%").resolve(4));
    }

    @Test
    public void negativePercentage() {
        Assert.assertEquals(3, MinimumShouldMatch.parse("-25%").resolve(4));
    }

    @Test
    public void combination() {
        MinimumShouldMatch mm = MinimumShouldMatch.parse("3<90%");
        Assert.assertEquals(3, mm.resolve(3));
        Assert.assertEquals(2, mm.resolve(2));
    }

    @Test
    public void multipleCombinations() {
        MinimumShouldMatch mm = MinimumShouldMatch.parse("2<-25% 9<-3");
        Assert.assertEquals(1, mm.resolve(1));
        Assert.assertEquals(2, mm.resolve(2));
        Assert.assertEquals(3, mm.resolve(4));
        Assert.assertEquals(7, mm.resolve(10));
    }

    @Test
    public void invalidSpecThrows() {
        try {
            MinimumShouldMatch.parse("abc");
            Assert.fail("expected exception");
        } catch (RuntimeException e) {
            Assert.assertTrue(e.getMessage().contains("minimum_should_match"));
        }
    }
}
