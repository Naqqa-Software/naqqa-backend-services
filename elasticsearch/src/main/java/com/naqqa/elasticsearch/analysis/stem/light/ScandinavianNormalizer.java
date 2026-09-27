package com.naqqa.elasticsearch.analysis.stem.light;

import java.util.EnumSet;
import java.util.Set;

public final class ScandinavianNormalizer extends CharArrayStemmer {

    public enum Folding {
        AA,
        AO,
        AE,
        OE,
        OO
    }

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

    private final boolean foldAa;
    private final boolean foldAo;
    private final boolean foldAe;
    private final boolean foldOe;
    private final boolean foldOo;

    public ScandinavianNormalizer() {
        this(EnumSet.allOf(Folding.class));
    }

    public ScandinavianNormalizer(Set<Folding> foldings) {
        this.foldAa = foldings.contains(Folding.AA);
        this.foldAo = foldings.contains(Folding.AO);
        this.foldAe = foldings.contains(Folding.AE);
        this.foldOe = foldings.contains(Folding.OE);
        this.foldOo = foldings.contains(Folding.OO);
    }

    @Override
    public int stem(char[] buffer, int length) {
        for (int i = 0; i < length; i++) {
            char c = buffer[i];
            if (c == LOWER_AE_SE) {
                buffer[i] = LOWER_AE;
            } else if (c == AE_SE) {
                buffer[i] = AE;
            } else if (c == LOWER_OE_SE) {
                buffer[i] = LOWER_OE;
            } else if (c == OE_SE) {
                buffer[i] = OE;
            } else if (length - 1 > i) {
                char n = buffer[i + 1];
                boolean nextA = n == 'a' || n == 'A';
                boolean nextO = n == 'o' || n == 'O';
                boolean nextE = n == 'e' || n == 'E';
                if (c == 'a' && (foldAa && nextA || foldAo && nextO)) {
                    length = delete(buffer, i + 1, length);
                    buffer[i] = LOWER_AA;
                } else if (c == 'A' && (foldAa && nextA || foldAo && nextO)) {
                    length = delete(buffer, i + 1, length);
                    buffer[i] = AA;
                } else if (c == 'a' && foldAe && nextE) {
                    length = delete(buffer, i + 1, length);
                    buffer[i] = LOWER_AE;
                } else if (c == 'A' && foldAe && nextE) {
                    length = delete(buffer, i + 1, length);
                    buffer[i] = AE;
                } else if (c == 'o' && (foldOe && nextE || foldOo && nextO)) {
                    length = delete(buffer, i + 1, length);
                    buffer[i] = LOWER_OE;
                } else if (c == 'O' && (foldOe && nextE || foldOo && nextO)) {
                    length = delete(buffer, i + 1, length);
                    buffer[i] = OE;
                }
            }
        }
        return length;
    }
}
