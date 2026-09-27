package com.naqqa.elasticsearch.action.search;

import java.util.List;

public final class SearchPhaseExecutionException extends RuntimeException {

    private final List<SearchResponse.Failure> failures;

    public SearchPhaseExecutionException(String phase, List<SearchResponse.Failure> failures) {
        super("search phase [" + phase + "] failed: " + failures);
        this.failures = failures;
    }

    public List<SearchResponse.Failure> failures() {
        return failures;
    }
}
