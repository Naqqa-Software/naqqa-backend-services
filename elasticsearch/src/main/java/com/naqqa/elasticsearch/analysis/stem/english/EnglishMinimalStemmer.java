package com.naqqa.elasticsearch.analysis.stem.english;

import com.naqqa.elasticsearch.analysis.stem.Stemmer;

public final class EnglishMinimalStemmer implements Stemmer {

    public EnglishMinimalStemmer() {
    }

    @Override
    public boolean stem(StringBuilder s) {
        int len = s.length();
        if (len < 3 || s.charAt(len - 1) != 's') {
            return false;
        }
        switch (s.charAt(len - 2)) {
            case 'u':
            case 's':
                return false;
            case 'e':
                if (len > 3 && s.charAt(len - 3) == 'i' && s.charAt(len - 4) != 'a' && s.charAt(len - 4) != 'e') {
                    s.setCharAt(len - 3, 'y');
                    s.setLength(len - 2);
                    return true;
                }
                char c = s.charAt(len - 3);
                if (c == 'i' || c == 'a' || c == 'o' || c == 'e') {
                    return false;
                }
                s.setLength(len - 1);
                return true;
            default:
                s.setLength(len - 1);
                return true;
        }
    }
}
