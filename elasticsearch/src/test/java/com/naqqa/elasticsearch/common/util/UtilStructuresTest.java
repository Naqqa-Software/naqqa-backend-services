package com.naqqa.elasticsearch.common.util;

import com.naqqa.elasticsearch.common.breaker.ChildMemoryCircuitBreaker;
import com.naqqa.elasticsearch.common.breaker.CircuitBreaker;
import com.naqqa.elasticsearch.common.exception.CircuitBreakingException;
import com.naqqa.elasticsearch.test.Assert;
import com.naqqa.elasticsearch.test.Test;

public class UtilStructuresTest {

    @Test
    public void testFixedBitSet() {
        FixedBitSet bits = new FixedBitSet(100);
        bits.set(5);
        bits.set(64);
        bits.set(99);
        Assert.assertTrue(bits.get(5));
        Assert.assertTrue(bits.get(64));
        Assert.assertFalse(bits.get(6));
        Assert.assertEquals(3, bits.cardinality());
        Assert.assertEquals(5, bits.nextSetBit(0));
        Assert.assertEquals(64, bits.nextSetBit(6));
        bits.clear(64);
        Assert.assertFalse(bits.get(64));
    }

    @Test
    public void testSparseFixedBitSet() {
        SparseFixedBitSet bits = new SparseFixedBitSet(1_000_000);
        bits.set(0);
        bits.set(999_999);
        bits.set(500_000);
        Assert.assertEquals(3, bits.cardinality());
        Assert.assertEquals(0, bits.nextSetBit(0));
        Assert.assertEquals(500_000, bits.nextSetBit(1));
        bits.clear(500_000);
        Assert.assertEquals(999_999, bits.nextSetBit(1));
    }

    private static final class IntMinQueue extends PriorityQueue<Integer> {
        IntMinQueue(int maxSize) {
            super(maxSize, false);
        }

        @Override
        protected boolean lessThan(Integer a, Integer b) {
            return a < b;
        }
    }

    @Test
    public void testPriorityQueueOrdering() {
        IntMinQueue queue = new IntMinQueue(3);
        queue.insertWithOverflow(5);
        queue.insertWithOverflow(1);
        queue.insertWithOverflow(9);
        queue.insertWithOverflow(2);
        queue.insertWithOverflow(7);
        Assert.assertEquals(3, queue.size());
        int[] popped = new int[3];
        for (int i = 0; i < 3; i++) {
            popped[i] = queue.pop();
        }
        Assert.assertEquals(5, popped[0]);
        Assert.assertEquals(7, popped[1]);
        Assert.assertEquals(9, popped[2]);
    }

    @Test
    public void testBigArraysAccountingAndBreaker() {
        ChildMemoryCircuitBreaker breaker = new ChildMemoryCircuitBreaker("test", 1024, 1.0, CircuitBreaker.Durability.TRANSIENT);
        BigArrays bigArrays = new BigArrays(new PageCacheRecycler(), breaker, "test");
        try (LongArray array = bigArrays.newLongArray(100)) {
            array.set(0, 42L);
            array.set(99, 7L);
            Assert.assertEquals(42L, array.get(0));
            Assert.assertEquals(7L, array.get(99));
            Assert.assertEquals(100L, array.size());
        }
        Assert.assertEquals(0L, breaker.getUsed());

        Assert.assertThrows(CircuitBreakingException.class, () -> bigArrays.newByteArray(10000));
    }
}
