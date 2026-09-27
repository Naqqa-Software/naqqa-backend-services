package com.naqqa.elasticsearch.analysis.stem.light;

public final class ScandinavianFolder extends CharArrayStemmer {

    private static final char AA = 'Å';
    private static final char LOWER_AA = 'å';
    private static final char AE = 'Æ';
    private static final char LOWER_AE = 'æ';
    private static final char AE_SE = 'Ä';
    private static final char LOWER_AE_SE = 'ä';
    private static final char OE = 'Ø';
    private static final char LOWER_OE = 'ø';
    private static final char OE_SE = 'Ö';
    private static final char LOWER_OE_SE = 'ö';

    public ScandinavianFolder() {
    }

    @Override
    public int stem(char[] buffer, int length) {
        for (int i = 0; i < length; i++) {
            char c = buffer[i];
            if (c == LOWER_AA || c == LOWER_AE_SE || c == LOWER_AE) {
                buffer[i] = 'a';
            } else if (c == AA || c == AE_SE || c == AE) {
                buffer[i] = 'A';
            } else if (c == LOWER_OE || c == LOWER_OE_SE) {
                buffer[i] = 'o';
            } else if (c == OE || c == OE_SE) {
                buffer[i] = 'O';
            } else if (length - 1 > i) {
                char n = buffer[i + 1];
                if ((c == 'a' || c == 'A')
                    && (n == 'a' || n == 'A' || n == 'e' || n == 'E' || n == 'o' || n == 'O')) {
                    length = delete(buffer, i + 1, length);
                } else if ((c == 'o' || c == 'O') && (n == 'e' || n == 'E' || n == 'o' || n == 'O')) {
                    length = delete(buffer, i + 1, length);
                }
            }
        }
        return length;
    }
}
