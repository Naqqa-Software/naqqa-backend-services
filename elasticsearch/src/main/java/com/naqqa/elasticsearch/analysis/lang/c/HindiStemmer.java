package com.naqqa.elasticsearch.analysis.lang.c;

public final class HindiStemmer extends SuffixStemmer {

    private static final String[] SUFFIXES = {
        "ियाँ",
        "ियों",
        "िया",
        "ता",
        "ती",
        "ते",
        "ना",
        "ने",
        "नी",
        "या",
        "ये",
        "ों",
        "ीं",
        "ां",
        "ें",
        "ी",
        "े",
        "ा",
        "ं"
    };

    @Override
    protected String[] suffixes() {
        return SUFFIXES;
    }
}
