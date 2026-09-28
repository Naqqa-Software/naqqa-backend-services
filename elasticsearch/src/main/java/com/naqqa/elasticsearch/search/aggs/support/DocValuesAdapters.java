package com.naqqa.elasticsearch.search.aggs.support;

import com.naqqa.elasticsearch.codec.docvalues.NumericDocValuesReader;
import com.naqqa.elasticsearch.codec.docvalues.SortedDocValuesReader;
import com.naqqa.elasticsearch.codec.docvalues.SortedNumericDocValuesReader;
import com.naqqa.elasticsearch.codec.docvalues.SortedSetDocValuesReader;
import com.naqqa.elasticsearch.common.bytes.BytesRef;
import com.naqqa.elasticsearch.common.geo.GeoPoint;

public final class DocValuesAdapters {

    private DocValuesAdapters() {
    }

    public static GeoPointValuesSource geoPointValues(NumericDocValuesReader reader) {
        return new GeoPointValuesSource() {
            private boolean present;

            @Override
            public boolean advanceExact(int doc) {
                present = reader.advanceExact(doc);
                return present;
            }

            @Override
            public int docValueCount() {
                return present ? 1 : 0;
            }

            @Override
            public GeoPoint nextValue() {
                long encoded = reader.longValue();
                float lat = Float.intBitsToFloat((int) (encoded >>> 32));
                float lon = Float.intBitsToFloat((int) encoded);
                return new GeoPoint(lat, lon);
            }
        };
    }

    public static LongValuesSource of(SortedNumericDocValuesReader reader) {
        return new LongValuesSource() {
            @Override
            public boolean advanceExact(int doc) {
                return reader.advanceExact(doc);
            }

            @Override
            public int docValueCount() {
                return reader.docValueCount();
            }

            @Override
            public long nextValue() {
                return reader.nextValue();
            }
        };
    }

    public static LongValuesSource of(NumericDocValuesReader reader) {
        return new LongValuesSource() {
            private boolean present;

            @Override
            public boolean advanceExact(int doc) {
                present = reader.advanceExact(doc);
                return present;
            }

            @Override
            public int docValueCount() {
                return present ? 1 : 0;
            }

            @Override
            public long nextValue() {
                return reader.longValue();
            }
        };
    }

    public static SortedSetValues of(SortedSetDocValuesReader reader, long valueCount) {
        return new SortedSetValues() {
            @Override
            public boolean advanceExact(int doc) {
                return reader.advanceExact(doc);
            }

            @Override
            public int docValueCount() {
                return reader.docValueCount();
            }

            @Override
            public long nextOrd() {
                return reader.nextOrd();
            }

            @Override
            public BytesRef lookupOrd(long ord) {
                return new BytesRef(reader.lookupOrd((int) ord));
            }

            @Override
            public long getValueCount() {
                return valueCount;
            }
        };
    }

    public static SortedSetValues of(SortedDocValuesReader reader) {
        return new SortedSetValues() {
            private boolean present;

            @Override
            public boolean advanceExact(int doc) {
                present = reader.advanceExact(doc);
                return present;
            }

            @Override
            public int docValueCount() {
                return present ? 1 : 0;
            }

            @Override
            public long nextOrd() {
                return reader.ordValue();
            }

            @Override
            public BytesRef lookupOrd(long ord) {
                return new BytesRef(reader.lookupOrd((int) ord));
            }

            @Override
            public long getValueCount() {
                return reader.valueCount();
            }
        };
    }
}
