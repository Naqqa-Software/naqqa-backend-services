package com.naqqa.elasticsearch.search.highlight;

public final class Match {

    final int start;
    final int end;
    final String term;
    final int weight;

    public Match(int start, int end, String term, int weight) {
        if (end < start) {
            throw new IllegalArgumentException("end must be >= start");
        }
        this.start = start;
        this.end = end;
        this.term = term;
        this.weight = weight <= 0 ? 1 : weight;
    }

    public Match(int start, int end, String term) {
        this(start, end, term, 1);
    }

    public int start() {
        return start;
    }

    public int end() {
        return end;
    }

    public String term() {
        return term;
    }

    public int weight() {
        return weight;
    }
}
