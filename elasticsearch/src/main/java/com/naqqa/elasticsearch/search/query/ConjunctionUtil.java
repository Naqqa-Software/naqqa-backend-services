package com.naqqa.elasticsearch.search.query;

import com.naqqa.elasticsearch.codec.DocIdSetIterator;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

public final class ConjunctionUtil {

    private ConjunctionUtil() {
    }

    public static DocIdSetIterator intersect(List<? extends DocIdSetIterator> iterators) {
        if (iterators.isEmpty()) {
            throw new IllegalArgumentException("no iterators to conjoin");
        }
        if (iterators.size() == 1) {
            return iterators.get(0);
        }
        List<DocIdSetIterator> sorted = new ArrayList<>(iterators);
        sorted.sort(Comparator.comparingLong(DocIdSetIterator::cost));
        return new ConjunctionDISI(sorted.toArray(new DocIdSetIterator[0]));
    }

    private static final class ConjunctionDISI extends DocIdSetIterator {
        private final DocIdSetIterator lead;
        private final DocIdSetIterator[] others;

        ConjunctionDISI(DocIdSetIterator[] all) {
            this.lead = all[0];
            this.others = Arrays.copyOfRange(all, 1, all.length);
        }

        @Override
        public int docID() {
            return lead.docID();
        }

        @Override
        public int nextDoc() throws IOException {
            return doNext(lead.nextDoc());
        }

        @Override
        public int advance(int target) throws IOException {
            return doNext(lead.advance(target));
        }

        private int doNext(int doc) throws IOException {
            advanceHead:
            while (true) {
                if (doc == NO_MORE_DOCS) {
                    return NO_MORE_DOCS;
                }
                for (DocIdSetIterator other : others) {
                    if (other.docID() < doc) {
                        int otherDoc = other.advance(doc);
                        if (otherDoc > doc) {
                            doc = lead.advance(otherDoc);
                            continue advanceHead;
                        }
                    }
                }
                return doc;
            }
        }

        @Override
        public long cost() {
            return lead.cost();
        }
    }
}
