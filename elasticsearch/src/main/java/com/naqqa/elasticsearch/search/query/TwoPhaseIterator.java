package com.naqqa.elasticsearch.search.query;

import com.naqqa.elasticsearch.codec.DocIdSetIterator;

import java.io.IOException;

public abstract class TwoPhaseIterator {

    protected final DocIdSetIterator approximation;

    protected TwoPhaseIterator(DocIdSetIterator approximation) {
        if (approximation == null) {
            throw new IllegalArgumentException("approximation cannot be null");
        }
        this.approximation = approximation;
    }

    public DocIdSetIterator approximation() {
        return approximation;
    }

    public abstract boolean matches() throws IOException;

    public abstract float matchCost();

    public static DocIdSetIterator asDocIdSetIterator(TwoPhaseIterator twoPhase) {
        return new TwoPhaseIteratorAsDISI(twoPhase);
    }

    private static final class TwoPhaseIteratorAsDISI extends DocIdSetIterator {
        private final TwoPhaseIterator twoPhase;
        private final DocIdSetIterator approximation;

        TwoPhaseIteratorAsDISI(TwoPhaseIterator twoPhase) {
            this.twoPhase = twoPhase;
            this.approximation = twoPhase.approximation();
        }

        @Override
        public int docID() {
            return approximation.docID();
        }

        @Override
        public int nextDoc() throws IOException {
            return doNext(approximation.nextDoc());
        }

        @Override
        public int advance(int target) throws IOException {
            return doNext(approximation.advance(target));
        }

        private int doNext(int doc) throws IOException {
            while (doc != NO_MORE_DOCS && !twoPhase.matches()) {
                doc = approximation.nextDoc();
            }
            return doc;
        }

        @Override
        public long cost() {
            return approximation.cost();
        }
    }
}
