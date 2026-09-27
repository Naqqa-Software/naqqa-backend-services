package com.naqqa.elasticsearch.analysis.stem.english;

import com.naqqa.elasticsearch.analysis.stem.Stemmer;

public final class EnglishPossessiveStemmer implements Stemmer {

    public EnglishPossessiveStemmer() {
    }

    @Override
    public boolean stem(StringBuilder word) {
        int len = word.length();
        if (len < 2) {
            return false;
        }
        char apostrophe = word.charAt(len - 2);
        char last = word.charAt(len - 1);
        if ((apostrophe == '\'' || apostrophe == '’' || apostrophe == '＇') && (last == 's' || last == 'S')) {
            word.setLength(len - 2);
            return true;
        }
        return false;
    }
}
