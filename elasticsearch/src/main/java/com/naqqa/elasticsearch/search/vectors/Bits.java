package com.naqqa.elasticsearch.search.vectors;

import java.util.BitSet;
import java.util.function.IntPredicate;

public interface Bits {

    boolean get(int index);

    int length();

    default int cardinality() {
        int count = 0;
        int n = length();
        for (int i = 0; i < n; i++) {
            if (get(i)) {
                count++;
            }
        }
        return count;
    }

    static Bits matchAll(int length) {
        return new Bits() {
            @Override
            public boolean get(int index) {
                return true;
            }

            @Override
            public int length() {
                return length;
            }

            @Override
            public int cardinality() {
                return length;
            }
        };
    }

    static Bits matchNone(int length) {
        return new Bits() {
            @Override
            public boolean get(int index) {
                return false;
            }

            @Override
            public int length() {
                return length;
            }

            @Override
            public int cardinality() {
                return 0;
            }
        };
    }

    static Bits fromPredicate(IntPredicate predicate, int length) {
        return new Bits() {
            @Override
            public boolean get(int index) {
                return predicate.test(index);
            }

            @Override
            public int length() {
                return length;
            }
        };
    }

    static Bits fromBitSet(BitSet set, int length) {
        return new Bits() {
            @Override
            public boolean get(int index) {
                return set.get(index);
            }

            @Override
            public int length() {
                return length;
            }

            @Override
            public int cardinality() {
                return set.get(0, Math.max(0, length)).cardinality();
            }
        };
    }

    static Bits fromBitSet(BitSet set) {
        return fromBitSet(set, set.length());
    }
}
