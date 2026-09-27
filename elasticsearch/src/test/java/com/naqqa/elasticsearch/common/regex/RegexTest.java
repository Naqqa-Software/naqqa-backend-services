package com.naqqa.elasticsearch.common.regex;

import com.naqqa.elasticsearch.test.Assert;
import com.naqqa.elasticsearch.test.Test;

public class RegexTest {

    @Test
    public void testSimpleMatchWildcards() {
        Assert.assertTrue(Regex.simpleMatch("foo*", "foobar"));
        Assert.assertTrue(Regex.simpleMatch("*bar", "foobar"));
        Assert.assertTrue(Regex.simpleMatch("f?o", "foo"));
        Assert.assertFalse(Regex.simpleMatch("f?o", "faoo"));
        Assert.assertTrue(Regex.simpleMatch("*", "anything"));
        Assert.assertFalse(Regex.simpleMatch("foo", "foobar"));
        Assert.assertTrue(Regex.simpleMatch(new String[] { "abc", "x*z" }, "xyz"));
    }

    @Test
    public void testIsSimpleMatchPattern() {
        Assert.assertTrue(Regex.isSimpleMatchPattern("a*b"));
        Assert.assertFalse(Regex.isSimpleMatchPattern("abc"));
    }
}
