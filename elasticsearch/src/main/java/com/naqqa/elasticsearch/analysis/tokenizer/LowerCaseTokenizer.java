package com.naqqa.elasticsearch.analysis.tokenizer;

public final class LowerCaseTokenizer extends LetterTokenizer {

    @Override
    public boolean incrementToken() {
        if (!super.incrementToken()) {
            return false;
        }
        char[] buf = token.buffer();
        int len = token.length();
        for (int i = 0; i < len; ) {
            int cp = Character.codePointAt(buf, i, len);
            int lower = Character.toLowerCase(cp);
            if (lower != cp && Character.charCount(lower) == Character.charCount(cp)) {
                if (Character.charCount(cp) == 1) {
                    buf[i] = (char) lower;
                } else {
                    buf[i] = Character.highSurrogate(lower);
                    buf[i + 1] = Character.lowSurrogate(lower);
                }
            }
            i += Character.charCount(cp);
        }
        return true;
    }
}
