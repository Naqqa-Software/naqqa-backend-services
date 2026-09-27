package com.naqqa.elasticsearch.search.execution;

public final class ScoreDoc {

    public final int doc;
    public final float score;

    public ScoreDoc(int doc, float score) {
        this.doc = doc;
        this.score = score;
    }

    @Override
    public String toString() {
        return "ScoreDoc(doc=" + doc + ", score=" + score + ")";
    }
}
