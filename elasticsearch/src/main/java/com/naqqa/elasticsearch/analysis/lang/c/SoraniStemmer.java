package com.naqqa.elasticsearch.analysis.lang.c;

public final class SoraniStemmer extends SuffixStemmer {

    private static final String[] SUFFIXES = {
        "ەکانی",
        "ەکان",
        "ەکە",
        "یەک",
        "ێک",
        "ان",
        "ی",
        "ە"
    };

    @Override
    protected String[] suffixes() {
        return SUFFIXES;
    }
}
