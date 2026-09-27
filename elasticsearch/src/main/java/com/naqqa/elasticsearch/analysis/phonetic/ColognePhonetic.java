package com.naqqa.elasticsearch.analysis.phonetic;

import java.util.Locale;

public class ColognePhonetic implements PhoneticEncoder {

    private static final char[] AEIJOUY = {'A', 'E', 'I', 'J', 'O', 'U', 'Y'};
    private static final char[] CSZ = {'C', 'S', 'Z'};
    private static final char[] FPVW = {'F', 'P', 'V', 'W'};
    private static final char[] GKQ = {'G', 'K', 'Q'};
    private static final char[] CKQ = {'C', 'K', 'Q'};
    private static final char[] AHKLOQRUX = {'A', 'H', 'K', 'L', 'O', 'Q', 'R', 'U', 'X'};
    private static final char[] SZ = {'S', 'Z'};
    private static final char[] AHKOQUX = {'A', 'H', 'K', 'O', 'Q', 'U', 'X'};
    private static final char[] DTX = {'D', 'T', 'X'};
    private static final char CHAR_IGNORE = '-';

    @Override
    public String encode(String input) {
        return SoundexUtils.emptyToNull(colognePhonetic(input));
    }

    public boolean isEncodeEqual(String text1, String text2) {
        return java.util.Objects.equals(colognePhonetic(text1), colognePhonetic(text2));
    }

    private static boolean arrayContains(char[] arr, char key) {
        for (char element : arr) {
            if (element == key) {
                return true;
            }
        }
        return false;
    }

    private static final class Output {
        private final char[] data;
        private int length;
        private char lastCode = '/';

        Output(int size) {
            this.data = new char[size];
        }

        boolean isEmpty() {
            return length == 0;
        }

        void put(char code) {
            boolean accept = code != CHAR_IGNORE;
            boolean nonZ = code != '0';
            if (accept && lastCode != code && (nonZ || length == 0)) {
                data[length++] = code;
            }
            if (nonZ && accept) {
                lastCode = code;
            }
        }

        @Override
        public String toString() {
            return new String(data, 0, length);
        }
    }

    private String colognePhonetic(String text) {
        if (text == null) {
            return null;
        }
        char[] input = preprocess(text);
        Output output = new Output(input.length * 2);
        char lastChar = CHAR_IGNORE;
        for (int i = 0; i < input.length; i++) {
            char chr = input[i];
            char nextChar = i + 1 < input.length ? input[i + 1] : CHAR_IGNORE;
            if (chr < 'A' || chr > 'Z') {
                continue;
            }
            if (arrayContains(AEIJOUY, chr)) {
                output.put('0');
            } else if (chr == 'B' || chr == 'P' && nextChar != 'H') {
                output.put('1');
            } else if ((chr == 'D' || chr == 'T') && !arrayContains(CSZ, nextChar)) {
                output.put('2');
            } else if (arrayContains(FPVW, chr)) {
                output.put('3');
            } else if (arrayContains(GKQ, chr)) {
                output.put('4');
            } else if (chr == 'X' && !arrayContains(CKQ, lastChar)) {
                output.put('4');
                output.put('8');
            } else if (chr == 'S' || chr == 'Z') {
                output.put('8');
            } else if (chr == 'C') {
                if (output.isEmpty()) {
                    if (arrayContains(AHKLOQRUX, nextChar)) {
                        output.put('4');
                    } else {
                        output.put('8');
                    }
                } else if (arrayContains(SZ, lastChar) || !arrayContains(AHKOQUX, nextChar)) {
                    output.put('8');
                } else {
                    output.put('4');
                }
            } else if (arrayContains(DTX, chr)) {
                output.put('8');
            } else {
                switch (chr) {
                    case 'R':
                        output.put('7');
                        break;
                    case 'L':
                        output.put('5');
                        break;
                    case 'M':
                    case 'N':
                        output.put('6');
                        break;
                    case 'H':
                        output.put(CHAR_IGNORE);
                        break;
                    default:
                        break;
                }
            }
            lastChar = chr;
        }
        return output.toString();
    }

    private static char[] preprocess(String text) {
        char[] chrs = text.toUpperCase(Locale.GERMAN).toCharArray();
        for (int index = 0; index < chrs.length; index++) {
            switch (chrs[index]) {
                case 'Ä':
                    chrs[index] = 'A';
                    break;
                case 'Ü':
                    chrs[index] = 'U';
                    break;
                case 'Ö':
                    chrs[index] = 'O';
                    break;
                default:
                    break;
            }
        }
        return chrs;
    }
}
