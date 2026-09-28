package com.naqqa.elasticsearch.analysis.lang.b;

public final class GreekStemmer extends AbstractCharStemmer {

    private final GreekStemmerCore core = new GreekStemmerCore();

    @Override
    int stem(char[] s, int len) {
        return core.stem(s, len);
    }
}
