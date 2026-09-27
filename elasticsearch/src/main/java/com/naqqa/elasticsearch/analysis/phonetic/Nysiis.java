package com.naqqa.elasticsearch.analysis.phonetic;

import java.util.regex.Pattern;

public class Nysiis implements PhoneticEncoder {

    private static final char[] CHARS_A = {'A'};
    private static final char[] CHARS_AF = {'A', 'F'};
    private static final char[] CHARS_C = {'C'};
    private static final char[] CHARS_FF = {'F', 'F'};
    private static final char[] CHARS_G = {'G'};
    private static final char[] CHARS_N = {'N'};
    private static final char[] CHARS_NN = {'N', 'N'};
    private static final char[] CHARS_S = {'S'};
    private static final char[] CHARS_SSS = {'S', 'S', 'S'};

    private static final Pattern PAT_MAC = Pattern.compile("^MAC");
    private static final Pattern PAT_KN = Pattern.compile("^KN");
    private static final Pattern PAT_K = Pattern.compile("^K");
    private static final Pattern PAT_PH_PF = Pattern.compile("^(PH|PF)");
    private static final Pattern PAT_SCH = Pattern.compile("^SCH");
    private static final Pattern PAT_EE_IE = Pattern.compile("(EE|IE)$");
    private static final Pattern PAT_DT_ETC = Pattern.compile("(DT|RT|RD|NT|ND)$");

    private static final char SPACE = ' ';
    private static final int TRUE_LENGTH = 6;

    private final boolean strict;

    public Nysiis() {
        this(true);
    }

    public Nysiis(boolean strict) {
        this.strict = strict;
    }

    public boolean isStrict() {
        return strict;
    }

    @Override
    public String encode(String input) {
        return SoundexUtils.emptyToNull(nysiis(input));
    }

    private static boolean isVowel(char c) {
        return c == 'A' || c == 'E' || c == 'I' || c == 'O' || c == 'U';
    }

    private static char[] transcodeRemaining(char prev, char curr, char next, char aNext) {
        if (curr == 'E' && next == 'V') {
            return CHARS_AF;
        }
        if (isVowel(curr)) {
            return CHARS_A;
        }
        switch (curr) {
            case 'Q':
                return CHARS_G;
            case 'Z':
                return CHARS_S;
            case 'M':
                return CHARS_N;
            case 'K':
                if (next == 'N') {
                    return CHARS_NN;
                }
                return CHARS_C;
            default:
                break;
        }
        if (curr == 'S' && next == 'C' && aNext == 'H') {
            return CHARS_SSS;
        }
        if (curr == 'P' && next == 'H') {
            return CHARS_FF;
        }
        if (curr == 'H' && (!isVowel(prev) || !isVowel(next))) {
            return new char[] {prev};
        }
        if (curr == 'W' && isVowel(prev)) {
            return new char[] {prev};
        }
        return new char[] {curr};
    }

    private String nysiis(String input) {
        if (input == null) {
            return null;
        }
        String str = SoundexUtils.clean(input);
        if (str.isEmpty()) {
            return str;
        }
        str = PAT_MAC.matcher(str).replaceFirst("MCC");
        str = PAT_KN.matcher(str).replaceFirst("NN");
        str = PAT_K.matcher(str).replaceFirst("C");
        str = PAT_PH_PF.matcher(str).replaceFirst("FF");
        str = PAT_SCH.matcher(str).replaceFirst("SSS");
        str = PAT_EE_IE.matcher(str).replaceFirst("Y");
        str = PAT_DT_ETC.matcher(str).replaceFirst("D");
        StringBuilder key = new StringBuilder(str.length());
        key.append(str.charAt(0));
        char[] chars = str.toCharArray();
        int len = chars.length;
        for (int i = 1; i < len; i++) {
            char next = i < len - 1 ? chars[i + 1] : SPACE;
            char aNext = i < len - 2 ? chars[i + 2] : SPACE;
            char[] transcoded = transcodeRemaining(chars[i - 1], chars[i], next, aNext);
            System.arraycopy(transcoded, 0, chars, i, transcoded.length);
            if (chars[i] != chars[i - 1]) {
                key.append(chars[i]);
            }
        }
        if (key.length() > 1) {
            char lastChar = key.charAt(key.length() - 1);
            if (lastChar == 'S') {
                key.deleteCharAt(key.length() - 1);
                lastChar = key.charAt(key.length() - 1);
            }
            if (key.length() > 2) {
                char last2Char = key.charAt(key.length() - 2);
                if (last2Char == 'A' && lastChar == 'Y') {
                    key.deleteCharAt(key.length() - 2);
                }
            }
            if (lastChar == 'A') {
                key.deleteCharAt(key.length() - 1);
            }
        }
        String string = key.toString();
        return strict ? string.substring(0, Math.min(TRUE_LENGTH, string.length())) : string;
    }
}
