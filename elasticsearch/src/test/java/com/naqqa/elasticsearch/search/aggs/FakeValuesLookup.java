package com.naqqa.elasticsearch.search.aggs;

import com.naqqa.elasticsearch.common.bytes.BytesRef;
import com.naqqa.elasticsearch.common.geo.GeoPoint;
import com.naqqa.elasticsearch.search.aggs.support.GeoPointValuesSource;
import com.naqqa.elasticsearch.search.aggs.support.LongValuesSource;
import com.naqqa.elasticsearch.search.aggs.support.SortedSetValues;
import com.naqqa.elasticsearch.search.aggs.support.ValuesLookup;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class FakeValuesLookup implements ValuesLookup {

    private final Map<String, Map<Integer, long[]>> longFields = new HashMap<>();
    private final Map<String, Boolean> floatingPoint = new HashMap<>();
    private final Map<String, Map<Integer, String[]>> bytesFields = new HashMap<>();
    private final Map<String, Map<Integer, GeoPoint[]>> geoFields = new HashMap<>();

    public FakeValuesLookup putLongs(String field, Map<Integer, long[]> docValues) {
        longFields.put(field, docValues);
        floatingPoint.put(field, false);
        return this;
    }

    public FakeValuesLookup putDoubles(String field, Map<Integer, double[]> docValues) {
        Map<Integer, long[]> encoded = new HashMap<>();
        for (Map.Entry<Integer, double[]> e : docValues.entrySet()) {
            long[] vals = new long[e.getValue().length];
            for (int i = 0; i < vals.length; i++) {
                vals[i] = com.naqqa.elasticsearch.codec.NumericUtils.doubleToSortableLong(e.getValue()[i]);
            }
            encoded.put(e.getKey(), vals);
        }
        longFields.put(field, encoded);
        floatingPoint.put(field, true);
        return this;
    }

    public FakeValuesLookup putStrings(String field, Map<Integer, String[]> docValues) {
        bytesFields.put(field, docValues);
        return this;
    }

    public FakeValuesLookup putGeoPoints(String field, Map<Integer, GeoPoint[]> docValues) {
        geoFields.put(field, docValues);
        return this;
    }

    @Override
    public LongValuesSource longValues(String field) {
        Map<Integer, long[]> docValues = longFields.getOrDefault(field, Map.of());
        return new LongValuesSource() {
            private long[] current;
            private int pos;

            @Override
            public boolean advanceExact(int doc) {
                current = docValues.get(doc);
                pos = 0;
                return current != null && current.length > 0;
            }

            @Override
            public int docValueCount() {
                return current == null ? 0 : current.length;
            }

            @Override
            public long nextValue() {
                return current[pos++];
            }
        };
    }

    @Override
    public boolean isFloatingPoint(String field) {
        return floatingPoint.getOrDefault(field, false);
    }

    @Override
    public SortedSetValues bytesValues(String field) {
        Map<Integer, String[]> docValues = bytesFields.getOrDefault(field, Map.of());
        List<String> dict = new ArrayList<>();
        Map<String, Integer> dictIndex = new LinkedHashMap<>();
        for (String[] values : docValues.values()) {
            for (String v : values) {
                if (!dictIndex.containsKey(v)) {
                    dictIndex.put(v, dict.size());
                    dict.add(v);
                }
            }
        }
        return new SortedSetValues() {
            private String[] current;
            private int pos;

            @Override
            public boolean advanceExact(int doc) {
                current = docValues.get(doc);
                pos = 0;
                return current != null && current.length > 0;
            }

            @Override
            public int docValueCount() {
                return current == null ? 0 : current.length;
            }

            @Override
            public long nextOrd() {
                return dictIndex.get(current[pos++]);
            }

            @Override
            public BytesRef lookupOrd(long ord) {
                return new BytesRef(dict.get((int) ord));
            }

            @Override
            public long getValueCount() {
                return dict.size();
            }
        };
    }

    @Override
    public GeoPointValuesSource geoPointValues(String field) {
        Map<Integer, GeoPoint[]> docValues = geoFields.getOrDefault(field, Map.of());
        return new GeoPointValuesSource() {
            private GeoPoint[] current;
            private int pos;

            @Override
            public boolean advanceExact(int doc) {
                current = docValues.get(doc);
                pos = 0;
                return current != null && current.length > 0;
            }

            @Override
            public int docValueCount() {
                return current == null ? 0 : current.length;
            }

            @Override
            public GeoPoint nextValue() {
                return current[pos++];
            }
        };
    }
}
