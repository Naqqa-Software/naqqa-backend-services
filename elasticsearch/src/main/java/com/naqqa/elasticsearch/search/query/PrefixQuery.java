package com.naqqa.elasticsearch.search.query;

import com.naqqa.elasticsearch.codec.postings.PostingsEnum;
import com.naqqa.elasticsearch.codec.terms.SeekStatus;
import com.naqqa.elasticsearch.codec.terms.TermsEnum;

import java.io.IOException;
import java.util.Arrays;

public final class PrefixQuery extends MultiTermQuery {

    private final byte[] prefix;

    public PrefixQuery(String field, byte[] prefix) {
        super(field);
        this.prefix = prefix;
    }

    public byte[] prefix() {
        return prefix;
    }

    @Override
    protected TermsEnum getTermsEnum(TermsEnum termsEnum) {
        return new PrefixTermsEnum(termsEnum, prefix);
    }

    @Override
    public String toString() {
        return "PrefixQuery(" + field + ", " + new String(prefix, java.nio.charset.StandardCharsets.UTF_8) + ")";
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof PrefixQuery pq && field.equals(pq.field) && Arrays.equals(prefix, pq.prefix);
    }

    @Override
    public int hashCode() {
        return field.hashCode() * 31 + Arrays.hashCode(prefix);
    }

    private static boolean startsWith(byte[] term, byte[] prefix) {
        if (term.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (term[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    private static final class PrefixTermsEnum implements TermsEnum {
        private final TermsEnum in;
        private final byte[] prefix;
        private boolean started;
        private boolean exhausted;
        private byte[] current;

        PrefixTermsEnum(TermsEnum in, byte[] prefix) {
            this.in = in;
            this.prefix = prefix;
        }

        @Override
        public byte[] next() throws IOException {
            if (exhausted) {
                return null;
            }
            byte[] t;
            if (!started) {
                started = true;
                SeekStatus status = in.seekCeil(prefix);
                t = status == SeekStatus.END ? null : in.term();
            } else {
                t = in.next();
            }
            if (t != null && startsWith(t, prefix)) {
                current = t;
                return t;
            }
            exhausted = true;
            current = null;
            return null;
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
