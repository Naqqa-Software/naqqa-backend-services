package com.naqqa.elasticsearch.common.bytes;

import com.naqqa.elasticsearch.test.Assert;
import com.naqqa.elasticsearch.test.Test;

public class BytesTest {

    @Test
    public void testBytesArrayBasics() {
        BytesArray arr = new BytesArray("hello world");
        Assert.assertEquals(11, arr.length());
        Assert.assertEquals("hello world", arr.utf8ToString());
        Assert.assertEquals((byte) 'h', arr.get(0));

        BytesReference slice = arr.slice(6, 5);
        Assert.assertEquals("world", slice.utf8ToString());
    }

    @Test
    public void testBytesRefEqualityAndCompare() {
        BytesRef a = new BytesRef("abc");
        BytesRef b = new BytesRef("abc");
        BytesRef c = new BytesRef("abd");
        Assert.assertEquals(a, b);
        Assert.assertEquals(a.hashCode(), b.hashCode());
        Assert.assertTrue(a.compareTo(c) < 0);
    }

    @Test
    public void testStreamInputFromBytesReference() throws Exception {
        BytesArray arr = new BytesArray(new byte[] { 1, 2, 3, 4 });
        var in = arr.streamInput();
        Assert.assertEquals((byte) 1, in.readByte());
        Assert.assertEquals((byte) 2, in.readByte());
    }
}
