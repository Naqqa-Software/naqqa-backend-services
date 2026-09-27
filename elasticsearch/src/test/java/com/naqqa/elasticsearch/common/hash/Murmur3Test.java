package com.naqqa.elasticsearch.common.hash;

import com.naqqa.elasticsearch.test.Assert;
import com.naqqa.elasticsearch.test.Test;

import java.nio.charset.StandardCharsets;

public class Murmur3Test {

    @Test
    public void testEmptyInputHashesToZero() {
        Assert.assertEquals(0, Murmur3HashFunction.hash32(new byte[0], 0, 0, 0));
    }

    @Test
    public void testDeterministic() {
        byte[] data = "the quick brown fox".getBytes(StandardCharsets.UTF_8);
        int h1 = Murmur3HashFunction.hash32(data, 0, data.length, 0);
        int h2 = Murmur3HashFunction.hash32(data, 0, data.length, 0);
        Assert.assertEquals(h1, h2);
        int hDifferentSeed = Murmur3HashFunction.hash32(data, 0, data.length, 42);
        Assert.assertNotEquals(h1, hDifferentSeed);
    }

    @Test
    public void testRoutingHashStableAcrossCalls() {
        int a = Murmur3HashFunction.hash("routing-key-1");
        int b = Murmur3HashFunction.hash("routing-key-1");
        int c = Murmur3HashFunction.hash("routing-key-2");
        Assert.assertEquals(a, b);
        Assert.assertNotEquals(a, c);
    }

    @Test
    public void testHash128Deterministic() {
        long[] h1 = Murmur3HashFunction.hash128("some-document-id");
        long[] h2 = Murmur3HashFunction.hash128("some-document-id");
        Assert.assertEquals(h1[0], h2[0]);
        Assert.assertEquals(h1[1], h2[1]);
    }
}
