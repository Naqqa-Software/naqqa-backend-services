package com.naqqa.elasticsearch.common.collect;

import com.naqqa.elasticsearch.test.Assert;
import com.naqqa.elasticsearch.test.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Random;

public class PrimitiveCollectionsTest {

    @Test
    public void testIntIntHashMapAgainstHashMap() {
        Random random = new Random(42);
        HashMap<Integer, Integer> reference = new HashMap<>();
        IntIntHashMap map = new IntIntHashMap();
        for (int i = 0; i < 5000; i++) {
            int key = random.nextInt(1000);
            int op = random.nextInt(3);
            if (op == 0) {
                int value = random.nextInt();
                reference.put(key, value);
                map.put(key, value);
            } else if (op == 1) {
                reference.remove(key);
                map.remove(key);
            } else {
                Integer expected = reference.get(key);
                boolean contains = map.containsKey(key);
                Assert.assertEquals(expected != null, contains);
                if (expected != null) {
                    Assert.assertEquals(expected.intValue(), map.get(key));
                }
            }
        }
        Assert.assertEquals(reference.size(), map.size());
        for (Map_Entry e : entries(reference)) {
            Assert.assertEquals(e.value, map.get(e.key));
        }
    }

    private record Map_Entry(int key, int value) {
    }

    private static Iterable<Map_Entry> entries(HashMap<Integer, Integer> reference) {
        java.util.List<Map_Entry> list = new java.util.ArrayList<>();
        for (var e : reference.entrySet()) {
            list.add(new Map_Entry(e.getKey(), e.getValue()));
        }
        return list;
    }

    @Test
    public void testLongObjectHashMapAgainstHashMap() {
        Random random = new Random(7);
        HashMap<Long, String> reference = new HashMap<>();
        LongObjectHashMap<String> map = new LongObjectHashMap<>();
        for (int i = 0; i < 3000; i++) {
            long key = random.nextInt(500);
            int op = random.nextInt(3);
            if (op == 0) {
                String value = "v" + random.nextInt(100);
                reference.put(key, value);
                map.put(key, value);
            } else if (op == 1) {
                reference.remove(key);
                map.remove(key);
            } else {
                Assert.assertEquals(reference.get(key), map.get(key));
            }
        }
        Assert.assertEquals(reference.size(), map.size());
    }

    @Test
    public void testIntHashSetAndLongHashSet() {
        Random random = new Random(99);
        HashSet<Integer> reference = new HashSet<>();
        IntHashSet set = new IntHashSet();
        for (int i = 0; i < 2000; i++) {
            int value = random.nextInt(300);
            if (random.nextBoolean()) {
                reference.add(value);
                set.add(value);
            } else {
                reference.remove(value);
                set.remove(value);
            }
        }
        Assert.assertEquals(reference.size(), set.size());
        for (int v : set.toArray()) {
            Assert.assertTrue(reference.contains(v));
        }
    }

    @Test
    public void testArrayLists() {
        IntArrayList intList = new IntArrayList();
        for (int i = 0; i < 100; i++) {
            intList.add(i * 2);
        }
        Assert.assertEquals(100, intList.size());
        Assert.assertEquals(50, intList.get(25));

        LongArrayList longList = new LongArrayList();
        longList.add(Long.MAX_VALUE);
        Assert.assertEquals(Long.MAX_VALUE, longList.get(0));

        FloatArrayList floatList = new FloatArrayList();
        floatList.add(1.5f);
        Assert.assertEquals(1.5f, floatList.get(0), 0.0001);
    }
}
