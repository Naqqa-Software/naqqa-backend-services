package com.naqqa.elasticsearch.search.query;

public enum ScoreMode {

    COMPLETE(true),
    COMPLETE_NO_SCORES(false),
    TOP_SCORES(true),
    TOP_DOCS(false),
    TOP_DOCS_WITH_SCORES(true);

    private final boolean needsScores;

    ScoreMode(boolean needsScores) {
        this.needsScores = needsScores;
    }

    public boolean needsScores() {
        return needsScores;
    }

    public boolean isExhaustive() {
        return this == COMPLETE || this == COMPLETE_NO_SCORES;
    }
}
