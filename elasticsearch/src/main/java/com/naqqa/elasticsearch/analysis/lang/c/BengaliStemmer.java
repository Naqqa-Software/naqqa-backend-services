package com.naqqa.elasticsearch.analysis.lang.c;

public final class BengaliStemmer extends SuffixStemmer {

    private static final String[] SUFFIXES = {
        "গুলো",
        "গুলি",
        "দের",
        "েরা",
        "ের",
        "কে",
        "তে",
        "ছে",
        "বে",
        "রা",
        "টি",
        "টা",
        "লো",
        "ে",
        "ি",
        "া",
        "ল"
    };

    @Override
    protected String[] suffixes() {
        return SUFFIXES;
    }
}
