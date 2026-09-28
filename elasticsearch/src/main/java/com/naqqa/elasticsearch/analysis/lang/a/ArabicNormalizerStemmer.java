package com.naqqa.elasticsearch.analysis.lang.a;

import com.naqqa.elasticsearch.analysis.stem.light.CharArrayStemmer;

public final class ArabicNormalizerStemmer extends CharArrayStemmer {

    static final char ALEF = 'ا';
    static final char ALEF_MADDA = 'آ';
    static final char ALEF_HAMZA_ABOVE = 'أ';
    static final char ALEF_HAMZA_BELOW = 'إ';

    static final char YEH = 'ي';
    static final char DOTLESS_YEH = 'ى';

    static final char TEH_MARBUTA = 'ة';
    static final char HEH = 'ه';

    static final char TATWEEL = 'ـ';

    static final char FATHATAN = 'ً';
    static final char DAMMATAN = 'ٌ';
    static final char KASRATAN = 'ٍ';
    static final char FATHA = 'َ';
    static final char DAMMA = 'ُ';
    static final char KASRA = 'ِ';
    static final char SHADDA = 'ّ';
    static final char SUKUN = 'ْ';

    public ArabicNormalizerStemmer() {
    }

    @Override
    public int stem(char[] s, int len) {
        for (int i = 0; i < len; i++) {
            switch (s[i]) {
                case ALEF_MADDA:
                case ALEF_HAMZA_ABOVE:
                case ALEF_HAMZA_BELOW:
                    s[i] = ALEF;
                    break;
                case DOTLESS_YEH:
                    s[i] = YEH;
                    break;
                case TEH_MARBUTA:
                    s[i] = HEH;
                    break;
                case TATWEEL:
                case KASRATAN:
                case DAMMATAN:
                case FATHATAN:
                case FATHA:
                case DAMMA:
                case KASRA:
                case SHADDA:
                case SUKUN:
                    len = deleteAt(s, i, len);
                    i--;
                    break;
                default:
                    break;
            }
        }
        return len;
    }

    private static int deleteAt(char[] s, int pos, int len) {
        if (pos < len - 1) {
            System.arraycopy(s, pos + 1, s, pos, len - pos - 1);
        }
        return len - 1;
    }
}
