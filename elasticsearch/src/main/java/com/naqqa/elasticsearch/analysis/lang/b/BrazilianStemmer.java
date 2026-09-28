package com.naqqa.elasticsearch.analysis.lang.b;

import com.naqqa.elasticsearch.analysis.stem.Stemmer;

public final class BrazilianStemmer implements Stemmer {

    @Override
    public boolean stem(StringBuilder word) {
        String term = word.toString();
        String stemmed = new BrazilianStemmerCore().stem(term);
        if (stemmed == null || stemmed.equals(term)) {
            return false;
        }
        word.setLength(0);
        word.append(stemmed);
        return true;
    }
}
