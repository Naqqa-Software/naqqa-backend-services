package com.naqqa.elasticsearch.search.execution;

public final class Sort {

    public static final Sort RELEVANCE = new Sort(new SortField(SortField.Type.SCORE));
    public static final Sort INDEX_ORDER = new Sort(new SortField(SortField.Type.DOC));

    private final SortField[] fields;

    public Sort(SortField... fields) {
        if (fields.length == 0) {
            throw new IllegalArgumentException("Sort requires at least one SortField");
        }
        this.fields = fields;
    }

    public SortField[] fields() {
        return fields;
    }
}
