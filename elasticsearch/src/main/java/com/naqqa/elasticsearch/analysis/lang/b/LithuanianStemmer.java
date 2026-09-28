package com.naqqa.elasticsearch.analysis.lang.b;

import com.naqqa.elasticsearch.analysis.stem.Stemmer;

public final class LithuanianStemmer implements Stemmer {

    @Override
    public boolean stem(StringBuilder word) {
        String term = word.toString();
        LithuanianSnowballCore core = new LithuanianSnowballCore();
        core.setCurrent(term);
        core.stem();
        String stemmed = core.getCurrent();
        if (stemmed.equals(term)) {
            return false;
        }
        word.setLength(0);
        word.append(stemmed);
        return true;
    }
}
