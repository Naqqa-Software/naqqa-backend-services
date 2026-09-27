package com.naqqa.elasticsearch.search.vectors;

public interface RandomVectorScorer {

    float score(int ord);

    int maxOrd();

    default int ordToDoc(int ord) {
        return ord;
    }

    default Bits acceptOrds(Bits acceptDocs) {
        if (acceptDocs == null) {
            return null;
        }
        int max = maxOrd();
        return new Bits() {
            @Override
            public boolean get(int ord) {
                int doc = ordToDoc(ord);
                return doc >= 0 && doc < acceptDocs.length() && acceptDocs.get(doc);
            }

            @Override
            public int length() {
                return max;
            }
        };
    }
}
