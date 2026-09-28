package com.naqqa.elasticsearch.search.execution;

public final class FieldDoc {

    public final int doc;
    public final float score;
    public final Object[] values;

    public FieldDoc(int doc, float score, Object[] values) {
        this.doc = doc;
        this.score = score;
        this.values = values;
    }

    @Override
    public String toString() {
        return "FieldDoc(doc=" + doc + ", score=" + score + ")";
    }
}
