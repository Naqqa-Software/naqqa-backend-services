package com.naqqa.elasticsearch.search.query;

import com.naqqa.elasticsearch.codec.postings.PostingsEnum;
import com.naqqa.elasticsearch.codec.terms.SeekStatus;
import com.naqqa.elasticsearch.codec.terms.TermsEnum;
import com.naqqa.elasticsearch.common.automaton.Automaton;
import com.naqqa.elasticsearch.common.automaton.CompiledAutomaton;
import com.naqqa.elasticsearch.common.automaton.LevenshteinAutomata;
import com.naqqa.elasticsearch.common.automaton.RegExp;
import com.naqqa.elasticsearch.common.automaton.SortedTermSource;
import com.naqqa.elasticsearch.common.automaton.WildcardAutomata;

import java.io.IOException;
import java.io.UncheckedIOException;

public final class AutomatonQuery extends MultiTermQuery {

    private final CompiledAutomaton compiled;
    private final String description;

    public AutomatonQuery(String field, Automaton automaton, String description) {
        super(field);
        this.compiled = new CompiledAutomaton(automaton, false, Integer.MAX_VALUE);
        this.description = description;
    }

    public static AutomatonQuery wildcard(String field, String pattern) {
        return wildcard(field, pattern, false);
    }

    public static AutomatonQuery wildcard(String field, String pattern, boolean caseInsensitive) {
        Automaton a = WildcardAutomata.toAutomaton(pattern, caseInsensitive);
        return new AutomatonQuery(field, a, "wildcard:" + pattern);
    }

    public static AutomatonQuery regexp(String field, String pattern) {
        return regexp(field, pattern, RegExp.ALL);
    }

    public static AutomatonQuery regexp(String field, String pattern, int flags) {
        Automaton a = new RegExp(pattern, flags).toAutomaton(Integer.MAX_VALUE);
        return new AutomatonQuery(field, a, "regexp:" + pattern);
    }

    public static AutomatonQuery fuzzy(String field, String text, int maxEdits, int prefixLength, boolean transpositions) {
        Automaton a = new LevenshteinAutomata(text, maxEdits, transpositions).toAutomaton(prefixLength);
        return new AutomatonQuery(field, a, "fuzzy:" + text + "~" + maxEdits);
    }

    @Override
    protected TermsEnum getTermsEnum(TermsEnum termsEnum) throws IOException {
        switch (compiled.getType()) {
            case NONE:
                return EmptyTermsEnum.INSTANCE;
            case ALL:
                return termsEnum;
            case SINGLE:
                return new SingleTermTermsEnum(termsEnum, compiled.getTerm());
            default:
                TermsEnumSource source = new TermsEnumSource(termsEnum);
                CompiledAutomaton.TermIterator it = compiled.intersect(source);
                return new AutomatonMatchingTermsEnum(source, it);
        }
    }

    @Override
    public String toString() {
        return "AutomatonQuery(" + field + ", " + description + ")";
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof AutomatonQuery aq && field.equals(aq.field) && description.equals(aq.description);
    }

    @Override
    public int hashCode() {
        return field.hashCode() * 31 + description.hashCode();
    }

    private static final class TermsEnumSource implements SortedTermSource {
        final TermsEnum te;

        TermsEnumSource(TermsEnum te) {
            this.te = te;
        }

        @Override
        public byte[] seekCeil(byte[] target) {
            try {
                SeekStatus status = te.seekCeil(target);
                return status == SeekStatus.END ? null : te.term();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public byte[] next() {
            try {
                return te.next();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    private static final class AutomatonMatchingTermsEnum implements TermsEnum {
        private final TermsEnumSource source;
        private final CompiledAutomaton.TermIterator it;
        private byte[] current;

        AutomatonMatchingTermsEnum(TermsEnumSource source, CompiledAutomaton.TermIterator it) {
            this.source = source;
            this.it = it;
        }

        @Override
        public byte[] next() {
            current = it.next();
            return current;
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
            return source.te.docFreq();
        }

        @Override
        public long totalTermFreq() throws IOException {
            return source.te.totalTermFreq();
        }

        @Override
        public PostingsEnum postings(int flags) throws IOException {
            return source.te.postings(flags);
        }
    }

    private static final class SingleTermTermsEnum implements TermsEnum {
        private final TermsEnum in;
        private final byte[] term;
        private boolean done;
        private boolean found;

        SingleTermTermsEnum(TermsEnum in, byte[] term) {
            this.in = in;
            this.term = term;
        }

        @Override
        public byte[] next() throws IOException {
            if (done) {
                return null;
            }
            done = true;
            found = in.seekExact(term);
            return found ? term : null;
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
            return found ? term : null;
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

    private static final class EmptyTermsEnum implements TermsEnum {
        static final EmptyTermsEnum INSTANCE = new EmptyTermsEnum();

        @Override
        public byte[] next() {
            return null;
        }

        @Override
        public boolean seekExact(byte[] text) {
            return false;
        }

        @Override
        public SeekStatus seekCeil(byte[] text) {
            return SeekStatus.END;
        }

        @Override
        public byte[] term() {
            return null;
        }

        @Override
        public int docFreq() {
            return 0;
        }

        @Override
        public long totalTermFreq() {
            return 0;
        }

        @Override
        public PostingsEnum postings(int flags) {
            throw new IllegalStateException("no current term");
        }
    }
}
