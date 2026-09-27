package com.naqqa.elasticsearch.analysis.stem.english;

import com.naqqa.elasticsearch.analysis.stem.Stemmer;

public final class EnglishStemmer implements Stemmer {

    private static final String[] EXCEPTION1_WORDS = {
        "skis", "skies", "dying", "lying", "tying", "idly", "gently", "ugly", "early", "only", "singly",
        "sky", "news", "howe", "atlas", "cosmos", "bias", "andes"
    };

    private static final String[] EXCEPTION1_STEMS = {
        "ski", "sky", "die", "lie", "tie", "idl", "gentl", "ugli", "earli", "onli", "singl",
        "sky", "news", "howe", "atlas", "cosmos", "bias", "andes"
    };

    private static final String[] EXCEPTION2_WORDS = {
        "inning", "outing", "canning", "herring", "earring", "proceed", "exceed", "succeed"
    };

    private static final String[] REGION_PREFIXES = {"gener", "commun", "arsen"};

    private static final String[] STEP1B_SUFFIXES = {"eedly", "ingly", "edly", "eed", "ing", "ed"};

    private static final String[] STEP2_SUFFIXES = {
        "ational", "ization", "fulness", "ousness", "iveness",
        "tional", "biliti", "lessli",
        "entli", "ation", "alism", "aliti", "ousli", "iviti", "fulli",
        "enci", "anci", "abli", "izer", "ator", "alli",
        "bli", "ogi",
        "li"
    };

    private static final String[] STEP2_REPLACEMENTS = {
        "ate", "ize", "ful", "ous", "ive",
        "tion", "ble", "less",
        "ent", "ate", "al", "al", "ous", "ive", "ful",
        "ence", "ance", "able", "ize", "ate", "al",
        "ble", null,
        null
    };

    private static final String[] STEP3_SUFFIXES = {
        "ational", "tional", "alize", "icate", "iciti", "ative", "ical", "ness", "ful"
    };

    private static final String[] STEP3_REPLACEMENTS = {
        "ate", "tion", "al", "ic", "ic", null, "ic", "", ""
    };

    private static final String[] STEP4_SUFFIXES = {
        "ement", "ance", "ence", "able", "ible", "ment",
        "ant", "ent", "ism", "ate", "iti", "ous", "ive", "ize", "ion",
        "al", "er", "ic"
    };

    private char[] b = new char[64];
    private int n;
    private int p1;
    private int p2;

    public EnglishStemmer() {
    }

    @Override
    public boolean stem(StringBuilder word) {
        int len = word.length();
        if (b.length < len + 4) {
            b = new char[Math.max(len + 4, b.length * 2)];
        }
        word.getChars(0, len, b, 0);
        n = len;
        String exception = exception1();
        if (exception != null) {
            return writeBack(word, exception);
        }
        if (n < 3) {
            return false;
        }
        boolean yFound = prelude();
        markRegions();
        step1a();
        if (!exception2()) {
            step1b();
            step1c();
            step2();
            step3();
            step4();
            step5();
        }
        if (yFound) {
            for (int i = 0; i < n; i++) {
                if (b[i] == 'Y') {
                    b[i] = 'y';
                }
            }
        }
        boolean changed = n != len;
        for (int i = 0; !changed && i < len; i++) {
            changed = b[i] != word.charAt(i);
        }
        if (changed) {
            word.setLength(0);
            word.append(b, 0, n);
        }
        return changed;
    }

    private static boolean writeBack(StringBuilder word, String value) {
        if (value.contentEquals(word)) {
            return false;
        }
        word.setLength(0);
        word.append(value);
        return true;
    }

    private boolean equalsWord(String s) {
        if (s.length() != n) {
            return false;
        }
        for (int i = 0; i < n; i++) {
            if (b[i] != s.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    private String exception1() {
        for (int i = 0; i < EXCEPTION1_WORDS.length; i++) {
            if (equalsWord(EXCEPTION1_WORDS[i])) {
                return EXCEPTION1_STEMS[i];
            }
        }
        return null;
    }

    private boolean exception2() {
        for (String w : EXCEPTION2_WORDS) {
            if (equalsWord(w)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isVowel(char c) {
        return c == 'a' || c == 'e' || c == 'i' || c == 'o' || c == 'u' || c == 'y';
    }

    private static boolean isVowelWXY(char c) {
        return isVowel(c) || c == 'w' || c == 'x' || c == 'Y';
    }

    private boolean prelude() {
        boolean yFound = false;
        if (n > 0 && b[0] == '\'') {
            System.arraycopy(b, 1, b, 0, n - 1);
            n--;
        }
        if (n > 0 && b[0] == 'y') {
            b[0] = 'Y';
            yFound = true;
        }
        for (int i = 1; i < n; i++) {
            if (b[i] == 'y' && isVowel(b[i - 1])) {
                b[i] = 'Y';
                yFound = true;
            }
        }
        return yFound;
    }

    private void markRegions() {
        p1 = n;
        p2 = n;
        int start = -1;
        for (String prefix : REGION_PREFIXES) {
            if (startsWith(prefix)) {
                start = prefix.length();
                break;
            }
        }
        if (start < 0) {
            start = pastVowelNonVowel(0);
            if (start < 0) {
                return;
            }
        }
        p1 = start;
        int second = pastVowelNonVowel(p1);
        if (second >= 0) {
            p2 = second;
        }
    }

    private boolean startsWith(String prefix) {
        if (prefix.length() > n) {
            return false;
        }
        for (int i = 0; i < prefix.length(); i++) {
            if (b[i] != prefix.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    private int pastVowelNonVowel(int from) {
        int i = from;
        while (i < n && !isVowel(b[i])) {
            i++;
        }
        if (i >= n) {
            return -1;
        }
        i++;
        while (i < n && isVowel(b[i])) {
            i++;
        }
        if (i >= n) {
            return -1;
        }
        return i + 1;
    }

    private boolean endsWith(String s) {
        int l = s.length();
        if (l > n) {
            return false;
        }
        int o = n - l;
        for (int i = 0; i < l; i++) {
            if (b[o + i] != s.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    private int longest(String[] suffixes) {
        for (int i = 0; i < suffixes.length; i++) {
            if (endsWith(suffixes[i])) {
                return i;
            }
        }
        return -1;
    }

    private void replaceSuffix(int suffixLength, String replacement) {
        int start = n - suffixLength;
        for (int i = 0; i < replacement.length(); i++) {
            b[start + i] = replacement.charAt(i);
        }
        n = start + replacement.length();
    }

    private boolean shortV(int end) {
        if (end >= 3 && !isVowelWXY(b[end - 1]) && isVowel(b[end - 2]) && !isVowel(b[end - 3])) {
            return true;
        }
        return end == 2 && !isVowel(b[1]) && isVowel(b[0]);
    }

    private void step1a() {
        if (endsWith("'s'")) {
            n -= 3;
        } else if (endsWith("'s")) {
            n -= 2;
        } else if (endsWith("'")) {
            n -= 1;
        }
        if (endsWith("sses")) {
            n -= 2;
        } else if (endsWith("ied") || endsWith("ies")) {
            if (n - 3 >= 2) {
                replaceSuffix(3, "i");
            } else {
                replaceSuffix(3, "ie");
            }
        } else if (endsWith("us") || endsWith("ss")) {
            return;
        } else if (endsWith("s")) {
            for (int i = 0; i <= n - 3; i++) {
                if (isVowel(b[i])) {
                    n--;
                    return;
                }
            }
        }
    }

    private void step1b() {
        int index = longest(STEP1B_SUFFIXES);
        if (index < 0) {
            return;
        }
        String suffix = STEP1B_SUFFIXES[index];
        int start = n - suffix.length();
        if (index == 0 || index == 3) {
            if (start >= p1) {
                replaceSuffix(suffix.length(), "ee");
            }
            return;
        }
        boolean vowel = false;
        for (int i = 0; i < start; i++) {
            if (isVowel(b[i])) {
                vowel = true;
                break;
            }
        }
        if (!vowel) {
            return;
        }
        n = start;
        if (endsWith("at") || endsWith("bl") || endsWith("iz")) {
            b[n++] = 'e';
        } else if (n >= 2 && b[n - 1] == b[n - 2] && isDoubleCandidate(b[n - 1])) {
            n--;
        } else if (n == p1 && shortV(n)) {
            b[n++] = 'e';
        }
    }

    private static boolean isDoubleCandidate(char c) {
        switch (c) {
            case 'b':
            case 'd':
            case 'f':
            case 'g':
            case 'm':
            case 'n':
            case 'p':
            case 'r':
            case 't':
                return true;
            default:
                return false;
        }
    }

    private void step1c() {
        if (n >= 3 && (b[n - 1] == 'y' || b[n - 1] == 'Y') && !isVowel(b[n - 2])) {
            b[n - 1] = 'i';
        }
    }

    private void step2() {
        int index = longest(STEP2_SUFFIXES);
        if (index < 0) {
            return;
        }
        String suffix = STEP2_SUFFIXES[index];
        int start = n - suffix.length();
        if (start < p1) {
            return;
        }
        String replacement = STEP2_REPLACEMENTS[index];
        if (replacement != null) {
            replaceSuffix(suffix.length(), replacement);
        } else if (suffix.equals("ogi")) {
            if (start > 0 && b[start - 1] == 'l') {
                replaceSuffix(3, "og");
            }
        } else if (start > 0 && isValidLi(b[start - 1])) {
            n = start;
        }
    }

    private static boolean isValidLi(char c) {
        switch (c) {
            case 'c':
            case 'd':
            case 'e':
            case 'g':
            case 'h':
            case 'k':
            case 'm':
            case 'n':
            case 'r':
            case 't':
                return true;
            default:
                return false;
        }
    }

    private void step3() {
        int index = longest(STEP3_SUFFIXES);
        if (index < 0) {
            return;
        }
        String suffix = STEP3_SUFFIXES[index];
        int start = n - suffix.length();
        if (start < p1) {
            return;
        }
        String replacement = STEP3_REPLACEMENTS[index];
        if (replacement != null) {
            replaceSuffix(suffix.length(), replacement);
        } else if (start >= p2) {
            n = start;
        }
    }

    private void step4() {
        int index = longest(STEP4_SUFFIXES);
        if (index < 0) {
            return;
        }
        String suffix = STEP4_SUFFIXES[index];
        int start = n - suffix.length();
        if (start < p2) {
            return;
        }
        if (suffix.equals("ion")) {
            if (start > 0 && (b[start - 1] == 's' || b[start - 1] == 't')) {
                n = start;
            }
        } else {
            n = start;
        }
    }

    private void step5() {
        if (n == 0) {
            return;
        }
        int start = n - 1;
        if (b[start] == 'e') {
            if (start >= p2 || (start >= p1 && !shortV(start))) {
                n = start;
            }
        } else if (b[start] == 'l') {
            if (start >= p2 && start > 0 && b[start - 1] == 'l') {
                n = start;
            }
        }
    }
}
