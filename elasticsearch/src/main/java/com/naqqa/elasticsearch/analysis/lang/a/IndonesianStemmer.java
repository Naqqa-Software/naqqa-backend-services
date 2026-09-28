package com.naqqa.elasticsearch.analysis.lang.a;

import com.naqqa.elasticsearch.analysis.stem.light.CharArrayStemmer;

public final class IndonesianStemmer extends CharArrayStemmer {

    private static final int REMOVED_KE = 1;
    private static final int REMOVED_PENG = 2;
    private static final int REMOVED_DI = 4;
    private static final int REMOVED_MENG = 8;
    private static final int REMOVED_TER = 16;
    private static final int REMOVED_BER = 32;
    private static final int REMOVED_PE = 64;

    private final boolean stemDerivational;
    private int numSyllables;
    private int flags;

    public IndonesianStemmer() {
        this(true);
    }

    public IndonesianStemmer(boolean stemDerivational) {
        this.stemDerivational = stemDerivational;
    }

    @Override
    public int stem(char[] text, int length) {
        flags = 0;
        numSyllables = 0;
        for (int i = 0; i < length; i++) {
            if (isVowel(text[i])) {
                numSyllables++;
            }
        }
        if (numSyllables > 2) {
            length = removeParticle(text, length);
        }
        if (numSyllables > 2) {
            length = removePossessivePronoun(text, length);
        }
        if (stemDerivational) {
            length = stemDerivational(text, length);
        }
        return length;
    }

    private int stemDerivational(char[] text, int length) {
        int oldLength = length;
        if (numSyllables > 2) {
            length = removeFirstOrderPrefix(text, length);
        }
        if (oldLength != length) {
            oldLength = length;
            if (numSyllables > 2) {
                length = removeSuffix(text, length);
            }
            if (oldLength != length) {
                if (numSyllables > 2) {
                    length = removeSecondOrderPrefix(text, length);
                }
            }
        } else {
            if (numSyllables > 2) {
                length = removeSecondOrderPrefix(text, length);
            }
            if (numSyllables > 2) {
                length = removeSuffix(text, length);
            }
        }
        return length;
    }

    private boolean isVowel(char ch) {
        switch (ch) {
            case 'a':
            case 'e':
            case 'i':
            case 'o':
            case 'u':
                return true;
            default:
                return false;
        }
    }

    private int removeParticle(char[] text, int length) {
        if (endsWith(text, length, "kah") || endsWith(text, length, "lah") || endsWith(text, length, "pun")) {
            numSyllables--;
            return length - 3;
        }
        return length;
    }

    private int removePossessivePronoun(char[] text, int length) {
        if (endsWith(text, length, "ku") || endsWith(text, length, "mu")) {
            numSyllables--;
            return length - 2;
        }
        if (endsWith(text, length, "nya")) {
            numSyllables--;
            return length - 3;
        }
        return length;
    }

    private int removeFirstOrderPrefix(char[] text, int length) {
        if (startsWith(text, length, "meng")) {
            flags |= REMOVED_MENG;
            numSyllables--;
            return deleteFront(text, length, 4);
        }
        if (startsWith(text, length, "meny") && length > 4 && isVowel(text[4])) {
            flags |= REMOVED_MENG;
            text[3] = 's';
            numSyllables--;
            return deleteFront(text, length, 3);
        }
        if (startsWith(text, length, "men")) {
            flags |= REMOVED_MENG;
            numSyllables--;
            return deleteFront(text, length, 3);
        }
        if (startsWith(text, length, "mem")) {
            flags |= REMOVED_MENG;
            numSyllables--;
            return deleteFront(text, length, 3);
        }
        if (startsWith(text, length, "me")) {
            flags |= REMOVED_MENG;
            numSyllables--;
            return deleteFront(text, length, 2);
        }
        if (startsWith(text, length, "peng")) {
            flags |= REMOVED_PENG;
            numSyllables--;
            return deleteFront(text, length, 4);
        }
        if (startsWith(text, length, "peny") && length > 4 && isVowel(text[4])) {
            flags |= REMOVED_PENG;
            text[3] = 's';
            numSyllables--;
            return deleteFront(text, length, 3);
        }
        if (startsWith(text, length, "peny")) {
            flags |= REMOVED_PENG;
            numSyllables--;
            return deleteFront(text, length, 4);
        }
        if (startsWith(text, length, "pen") && length > 3 && isVowel(text[3])) {
            flags |= REMOVED_PENG;
            text[2] = 't';
            numSyllables--;
            return deleteFront(text, length, 2);
        }
        if (startsWith(text, length, "pen")) {
            flags |= REMOVED_PENG;
            numSyllables--;
            return deleteFront(text, length, 3);
        }
        if (startsWith(text, length, "pem")) {
            flags |= REMOVED_PENG;
            numSyllables--;
            return deleteFront(text, length, 3);
        }
        if (startsWith(text, length, "di")) {
            flags |= REMOVED_DI;
            numSyllables--;
            return deleteFront(text, length, 2);
        }
        if (startsWith(text, length, "ter")) {
            flags |= REMOVED_TER;
            numSyllables--;
            return deleteFront(text, length, 3);
        }
        if (startsWith(text, length, "ke")) {
            flags |= REMOVED_KE;
            numSyllables--;
            return deleteFront(text, length, 2);
        }
        return length;
    }

    private int removeSecondOrderPrefix(char[] text, int length) {
        if (startsWith(text, length, "ber")) {
            flags |= REMOVED_BER;
            numSyllables--;
            return deleteFront(text, length, 3);
        }
        if (length == 7 && startsWith(text, length, "belajar")) {
            flags |= REMOVED_BER;
            numSyllables--;
            return deleteFront(text, length, 3);
        }
        if (startsWith(text, length, "be") && length > 4 && !isVowel(text[2]) && text[3] == 'e' && text[4] == 'r') {
            flags |= REMOVED_BER;
            numSyllables--;
            return deleteFront(text, length, 2);
        }
        if (startsWith(text, length, "per")) {
            numSyllables--;
            return deleteFront(text, length, 3);
        }
        if (length == 7 && startsWith(text, length, "pelajar")) {
            numSyllables--;
            return deleteFront(text, length, 3);
        }
        if (startsWith(text, length, "pe")) {
            flags |= REMOVED_PE;
            numSyllables--;
            return deleteFront(text, length, 2);
        }
        return length;
    }

    private int removeSuffix(char[] text, int length) {
        if (endsWith(text, length, "kan") && (flags & REMOVED_KE) == 0 && (flags & REMOVED_PENG) == 0 && (flags & REMOVED_PE) == 0) {
            numSyllables--;
            return length - 3;
        }
        if (endsWith(text, length, "an") && (flags & REMOVED_DI) == 0 && (flags & REMOVED_MENG) == 0 && (flags & REMOVED_TER) == 0) {
            numSyllables--;
            return length - 2;
        }
        if (endsWith(text, length, "i") && !endsWith(text, length, "si") && (flags & REMOVED_BER) == 0 && (flags & REMOVED_KE) == 0 && (flags & REMOVED_PENG) == 0) {
            numSyllables--;
            return length - 1;
        }
        return length;
    }

    private static boolean startsWith(char[] s, int len, String prefix) {
        int prefixLen = prefix.length();
        if (prefixLen > len) {
            return false;
        }
        for (int i = 0; i < prefixLen; i++) {
            if (s[i] != prefix.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    private static boolean endsWith(char[] s, int len, String suffix) {
        int suffixLen = suffix.length();
        if (suffixLen > len) {
            return false;
        }
        for (int i = suffixLen - 1; i >= 0; i--) {
            if (s[len - (suffixLen - i)] != suffix.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    private static int deleteFront(char[] s, int len, int nChars) {
        if (nChars < len) {
            System.arraycopy(s, nChars, s, 0, len - nChars);
        }
        return len - nChars;
    }
}
