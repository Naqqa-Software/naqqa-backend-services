package com.naqqa.elasticsearch.search.bridge;

import com.naqqa.elasticsearch.codec.NumericUtils;
import com.naqqa.elasticsearch.codec.postings.PostingsEnum;
import com.naqqa.elasticsearch.codec.terms.SeekStatus;
import com.naqqa.elasticsearch.codec.terms.TermsEnum;
import com.naqqa.elasticsearch.search.query.MultiTermQuery;

import java.io.IOException;
import java.util.Arrays;

public final class TermRangeQuery extends MultiTermQuery {

    private final byte[] lowerTerm;
    private final byte[] upperTerm;
    private final boolean includeLower;
    private final boolean includeUpper;

    public TermRangeQuery(String field, byte[] lowerTerm, byte[] upperTerm, boolean includeLower, boolean includeUpper) {
        super(field);
        this.lowerTerm = lowerTerm;
        this.upperTerm = upperTerm;
        this.includeLower = includeLower;
        this.includeUpper = includeUpper;
    }

    @Override
    protected TermsEnum getTermsEnum(TermsEnum termsEnum) throws IOException {
        return new RangeTermsEnum(termsEnum, lowerTerm, upperTerm, includeLower, includeUpper);
    }

    @Override
    public String toString() {
        return "TermRangeQuery(" + field + ", [" + describe(lowerTerm) + " TO " + describe(upperTerm) + "])";
    }

    private static String describe(byte[] b) {
        return b == null ? "*" : new String(b, java.nio.charset.StandardCharsets.UTF_8);
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof TermRangeQuery q)) {
            return false;
        }
        return field.equals(q.field) && Arrays.equals(lowerTerm, q.lowerTerm) && Arrays.equals(upperTerm, q.upperTerm)
            && includeLower == q.includeLower && includeUpper == q.includeUpper;
    }

    @Override
    public int hashCode() {
        return field.hashCode() * 31 + Arrays.hashCode(lowerTerm) * 17 + Arrays.hashCode(upperTerm)
            + Boolean.hashCode(includeLower) + Boolean.hashCode(includeUpper);
    }

    private static final class RangeTermsEnum implements TermsEnum {
        private final TermsEnum in;
        private final byte[] lowerTerm;
        private final byte[] upperTerm;
        private final boolean includeLower;
        private final boolean includeUpper;
        private boolean started;
        private boolean exhausted;
        private byte[] current;

        RangeTermsEnum(TermsEnum in, byte[] lowerTerm, byte[] upperTerm, boolean includeLower, boolean includeUpper) {
            this.in = in;
            this.lowerTerm = lowerTerm;
            this.upperTerm = upperTerm;
            this.includeLower = includeLower;
            this.includeUpper = includeUpper;
        }

        @Override
        public byte[] next() throws IOException {
            if (exhausted) {
                return null;
            }
            byte[] t;
            if (!started) {
                started = true;
                if (lowerTerm == null) {
                    t = in.next();
                } else {
                    SeekStatus status = in.seekCeil(lowerTerm);
                    if (status == SeekStatus.END) {
                        exhausted = true;
                        return null;
                    }
                    t = in.term();
                    if (!includeLower && Arrays.equals(t, lowerTerm)) {
                        t = in.next();
                    }
                }
            } else {
                t = in.next();
            }
            if (t == null || !underUpper(t)) {
                exhausted = true;
                current = null;
                return null;
            }
            current = t;
            return t;
        }

        private boolean underUpper(byte[] t) {
            if (upperTerm == null) {
                return true;
            }
            int cmp = compareBytes(t, upperTerm);
            return includeUpper ? cmp <= 0 : cmp < 0;
        }

        private static int compareBytes(byte[] a, byte[] b) {
            int len = Math.min(a.length, b.length);
            int c = NumericUtils.compareUnsigned(a, 0, b, 0, len);
            if (c != 0) {
                return c;
            }
            return a.length - b.length;
        }

        @Override
        public boolean seekExact(byte[] text) {
            throw new UnsupportedOperationException("iteration-only terms enum");
        }

        @Override
        public SeekStatus seekCeil(byte[] text) {
            throw new UnsupportedOperationException("iteration-only terms enum");
        }

        @Override
        public byte[] term() {
            return current;
        }

        @Override
        public int docFreq() throws IOException {
            return in.docFreq();
        }

        @Override
        public long totalTermFreq() throws IOException {
            return in.totalTermFreq();
        }

        @Override
        public PostingsEnum postings(int flags) throws IOException {
            return in.postings(flags);
        }
    }
}
