package com.naqqa.elasticsearch.analysis.phonetic;

import java.util.Locale;

public class Metaphone implements PhoneticEncoder {

    private static final String VOWELS = "AEIOU";
    private static final String FRONTV = "EIY";
    private static final String VARSON = "CSPTG";

    private int maxCodeLen = 4;

    public Metaphone() {
    }

    public Metaphone(int maxCodeLen) {
        this.maxCodeLen = maxCodeLen;
    }

    public int getMaxCodeLen() {
        return maxCodeLen;
    }

    public void setMaxCodeLen(int maxCodeLen) {
        this.maxCodeLen = maxCodeLen;
    }

    @Override
    public String encode(String input) {
        return SoundexUtils.emptyToNull(metaphone(input));
    }

    public boolean isMetaphoneEqual(String str1, String str2) {
        return java.util.Objects.equals(encode(str1), encode(str2));
    }

    private static boolean isLastChar(int wdsz, int n) {
        return n + 1 == wdsz;
    }

    private static boolean isNextChar(StringBuilder string, int index, char c) {
        if (index >= 0 && index < string.length() - 1) {
            return string.charAt(index + 1) == c;
        }
        return false;
    }

    private static boolean isPreviousChar(StringBuilder string, int index, char c) {
        if (index > 0 && index < string.length()) {
            return string.charAt(index - 1) == c;
        }
        return false;
    }

    private static boolean isVowel(StringBuilder string, int index) {
        return VOWELS.indexOf(string.charAt(index)) >= 0;
    }

    private static boolean regionMatch(StringBuilder string, int index, String test) {
        if (index >= 0 && index + test.length() - 1 < string.length()) {
            return string.substring(index, index + test.length()).equals(test);
        }
        return false;
    }

    private String metaphone(String txt) {
        if (txt == null || txt.isEmpty()) {
            return "";
        }
        if (txt.length() == 1) {
            return txt.toUpperCase(Locale.ENGLISH);
        }
        char[] inwd = txt.toUpperCase(Locale.ENGLISH).toCharArray();
        StringBuilder local = new StringBuilder(40);
        StringBuilder code = new StringBuilder(10);
        switch (inwd[0]) {
            case 'K':
            case 'G':
            case 'P':
                if (inwd[1] == 'N') {
                    local.append(inwd, 1, inwd.length - 1);
                } else {
                    local.append(inwd);
                }
                break;
            case 'A':
                if (inwd[1] == 'E') {
                    local.append(inwd, 1, inwd.length - 1);
                } else {
                    local.append(inwd);
                }
                break;
            case 'W':
                if (inwd[1] == 'R') {
                    local.append(inwd, 1, inwd.length - 1);
                    break;
                }
                if (inwd[1] == 'H') {
                    local.append(inwd, 1, inwd.length - 1);
                    local.setCharAt(0, 'W');
                } else {
                    local.append(inwd);
                }
                break;
            case 'X':
                inwd[0] = 'S';
                local.append(inwd);
                break;
            default:
                local.append(inwd);
        }
        int wdsz = local.length();
        int n = 0;
        while (code.length() < maxCodeLen && n < wdsz) {
            char symb = local.charAt(n);
            if (symb == 'C' || !isPreviousChar(local, n, symb)) {
                switch (symb) {
                    case 'A':
                    case 'E':
                    case 'I':
                    case 'O':
                    case 'U':
                        if (n == 0) {
                            code.append(symb);
                        }
                        break;
                    case 'B':
                        if (isPreviousChar(local, n, 'M') && isLastChar(wdsz, n)) {
                            break;
                        }
                        code.append(symb);
                        break;
                    case 'C':
                        if (isPreviousChar(local, n, 'S') && !isLastChar(wdsz, n) && FRONTV.indexOf(local.charAt(n + 1)) >= 0) {
                            break;
                        }
                        if (isPreviousChar(local, n, 'S') && isNextChar(local, n, 'H')) {
                            code.append('K');
                            break;
                        }
                        if (regionMatch(local, n, "CIA") || isNextChar(local, n, 'H')) {
                            code.append('X');
                            break;
                        }
                        if (!isLastChar(wdsz, n) && FRONTV.indexOf(local.charAt(n + 1)) >= 0) {
                            code.append('S');
                            break;
                        }
                        code.append('K');
                        break;
                    case 'D':
                        if (!isLastChar(wdsz, n + 1) && isNextChar(local, n, 'G') && FRONTV.indexOf(local.charAt(n + 2)) >= 0) {
                            code.append('J');
                            n += 2;
                        } else {
                            code.append('T');
                        }
                        break;
                    case 'G':
                        if (isLastChar(wdsz, n + 1) && isNextChar(local, n, 'H')) {
                            break;
                        }
                        if (!isLastChar(wdsz, n + 1) && isNextChar(local, n, 'H') && !isVowel(local, n + 2)) {
                            break;
                        }
                        if (n > 0 && (regionMatch(local, n, "GN") || regionMatch(local, n, "GNED"))) {
                            break;
                        }
                        boolean hard = isPreviousChar(local, n, 'G');
                        if (!isLastChar(wdsz, n) && FRONTV.indexOf(local.charAt(n + 1)) >= 0 && !hard) {
                            code.append('J');
                        } else {
                            code.append('K');
                        }
                        break;
                    case 'H':
                        if (isLastChar(wdsz, n)) {
                            break;
                        }
                        if (n > 0 && VARSON.indexOf(local.charAt(n - 1)) >= 0) {
                            break;
                        }
                        if (isVowel(local, n + 1)) {
                            code.append('H');
                        }
                        break;
                    case 'F':
                    case 'J':
                    case 'L':
                    case 'M':
                    case 'N':
                    case 'R':
                        code.append(symb);
                        break;
                    case 'K':
                        if (n > 0) {
                            if (!isPreviousChar(local, n, 'C')) {
                                code.append(symb);
                            }
                        } else {
                            code.append(symb);
                        }
                        break;
                    case 'P':
                        if (isNextChar(local, n, 'H')) {
                            code.append('F');
                        } else {
                            code.append(symb);
                        }
                        break;
                    case 'Q':
                        code.append('K');
                        break;
                    case 'S':
                        if (regionMatch(local, n, "SH") || regionMatch(local, n, "SIO") || regionMatch(local, n, "SIA")) {
                            code.append('X');
                        } else {
                            code.append('S');
                        }
                        break;
                    case 'T':
                        if (regionMatch(local, n, "TIA") || regionMatch(local, n, "TIO")) {
                            code.append('X');
                            break;
                        }
                        if (regionMatch(local, n, "TCH")) {
                            break;
                        }
                        if (regionMatch(local, n, "TH")) {
                            code.append('0');
                        } else {
                            code.append('T');
                        }
                        break;
                    case 'V':
                        code.append('F');
                        break;
                    case 'W':
                    case 'Y':
                        if (!isLastChar(wdsz, n) && isVowel(local, n + 1)) {
                            code.append(symb);
                        }
                        break;
                    case 'X':
                        code.append('K');
                        code.append('S');
                        break;
                    case 'Z':
                        code.append('S');
                        break;
                    default:
                        break;
                }
            }
            n++;
            if (code.length() > maxCodeLen) {
                code.setLength(maxCodeLen);
            }
        }
        return code.toString();
    }
}
