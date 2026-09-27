package com.naqqa.elasticsearch.search.aggs.support;

import com.naqqa.elasticsearch.common.collect.LongArrayList;
import com.naqqa.elasticsearch.common.collect.LongLongHashMap;
import com.naqqa.elasticsearch.common.collect.LongObjectHashMap;

public final class BucketOrds {

    private static final LongArrayList EMPTY = new LongArrayList(1);

    private final LongObjectHashMap<LongLongHashMap> keyToOrd = new LongObjectHashMap<>();
    private final LongObjectHashMap<LongArrayList> ordsByOwning = new LongObjectHashMap<>();
    private final LongArrayList keysByOrd = new LongArrayList();
    private final LongArrayList owningByOrd = new LongArrayList();
    private long size = 0;

    public long add(long owningBucketOrd, long key) {
        LongLongHashMap map = keyToOrd.get(owningBucketOrd);
        if (map == null) {
            map = new LongLongHashMap();
            keyToOrd.put(owningBucketOrd, map);
        }
        long existing = map.get(key);
        if (existing != LongLongHashMap.NO_VALUE) {
            return -1L - existing;
        }
        long ord = size++;
        map.put(key, ord);
        keysByOrd.add(key);
        owningByOrd.add(owningBucketOrd);
        LongArrayList list = ordsByOwning.get(owningBucketOrd);
        if (list == null) {
            list = new LongArrayList();
            ordsByOwning.put(owningBucketOrd, list);
        }
        list.add(ord);
        return ord;
    }

    public long find(long owningBucketOrd, long key) {
        LongLongHashMap map = keyToOrd.get(owningBucketOrd);
        if (map == null) {
            return -1;
        }
        long v = map.get(key);
        return v == LongLongHashMap.NO_VALUE ? -1 : v;
    }

    public long size() {
        return size;
    }

    public long key(long bucketOrd) {
        return keysByOrd.get((int) bucketOrd);
    }

    public long owningOrd(long bucketOrd) {
        return owningByOrd.get((int) bucketOrd);
    }

    public LongArrayList ordsFor(long owningBucketOrd) {
        LongArrayList list = ordsByOwning.get(owningBucketOrd);
        return list == null ? EMPTY : list;
    }
}
