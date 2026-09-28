package com.naqqa.elasticsearch.analysis.lang.a;

import com.naqqa.elasticsearch.analysis.stem.light.CharArrayStemmer;

public final class ArabicLightStemmer extends CharArrayStemmer {

    private static final char ALEF = 'ا';
    private static final char BEH = 'ب';
    private static final char TEH_MARBUTA = 'ة';
    private static final char TEH = 'ت';
    private static final char FEH = 'ف';
    private static final char KAF = 'ك';
    private static final char LAM = 'ل';
    private static final char NOON = 'ن';
    private static final char HEH = 'ه';
    private static final char WAW = 'و';
    private static final char YEH = 'ي';

    private static final char[][] PREFIXES = {
        ("" + ALEF + LAM).toCharArray(),
        ("" + WAW + ALEF + LAM).toCharArray(),
        ("" + BEH + ALEF + LAM).toCharArray(),
        ("" + KAF + ALEF + LAM).toCharArray(),
        ("" + FEH + ALEF + LAM).toCharArray(),
        ("" + LAM + LAM).toCharArray(),
        ("" + WAW).toCharArray(),
    };

    private static final char[][] SUFFIXES = {
        ("" + HEH + ALEF).toCharArray(),
        ("" + ALEF + NOON).toCharArray(),
        ("" + ALEF + TEH).toCharArray(),
        ("" + WAW + NOON).toCharArray(),
        ("" + YEH + NOON).toCharArray(),
        ("" + YEH + HEH).toCharArray(),
        ("" + YEH + TEH_MARBUTA).toCharArray(),
        ("" + HEH).toCharArray(),
        ("" + TEH_MARBUTA).toCharArray(),
        ("" + YEH).toCharArray(),
    };

    public ArabicLightStemmer() {
    }

    @Override
    public int stem(char[] s, int len) {
        len = stemPrefix(s, len);
        len = stemSuffix(s, len);
        return len;
    }

    private int stemPrefix(char[] s, int len) {
        for (char[] prefix : PREFIXES) {
            if (startsWithCheckLength(s, len, prefix)) {
                return deleteN(s, 0, len, prefix.length);
            }
        }
        return len;
    }

    private int stemSuffix(char[] s, int len) {
        for (char[] suffix : SUFFIXES) {
            if (endsWithCheckLength(s, len, suffix)) {
                len = deleteN(s, len - suffix.length, len, suffix.length);
            }
        }
        return len;
    }

    private boolean startsWithCheckLength(char[] s, int len, char[] prefix) {
        if (prefix.length == 1 && len < 4) {
            return false;
        } else if (len < prefix.length + 2) {
            return false;
        } else {
            for (int i = 0; i < prefix.length; i++) {
                if (s[i] != prefix[i]) {
                    return false;
                }
            }
            return true;
        }
    }

    private boolean endsWithCheckLength(char[] s, int len, char[] suffix) {
        if (len < suffix.length + 2) {
            return false;
        } else {
            for (int i = 0; i < suffix.length; i++) {
                if (s[len - suffix.length + i] != suffix[i]) {
                    return false;
                }
            }
            return true;
        }
    }

    private static int deleteN(char[] s, int pos, int len, int nChars) {
        if (pos + nChars < len) {
            System.arraycopy(s, pos + nChars, s, pos, len - pos - nChars);
        }
        return len - nChars;
    }
}
