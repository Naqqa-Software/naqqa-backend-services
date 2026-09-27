package com.naqqa.elasticsearch.analysis.tokenizer;

public final class WordBreak {

    public static final byte OTHER = 0;
    public static final byte CR = 1;
    public static final byte LF = 2;
    public static final byte NEWLINE = 3;
    public static final byte EXTEND = 4;
    public static final byte ZWJ = 5;
    public static final byte REGIONAL_INDICATOR = 6;
    public static final byte FORMAT = 7;
    public static final byte KATAKANA = 8;
    public static final byte HEBREW_LETTER = 9;
    public static final byte ALETTER = 10;
    public static final byte SINGLE_QUOTE = 11;
    public static final byte DOUBLE_QUOTE = 12;
    public static final byte MID_NUM_LET = 13;
    public static final byte MID_LETTER = 14;
    public static final byte MID_NUM = 15;
    public static final byte NUMERIC = 16;
    public static final byte EXTEND_NUM_LET = 17;
    public static final byte WSEG_SPACE = 18;
    public static final byte IDEOGRAPHIC = 19;
    public static final byte HIRAGANA = 20;
    public static final byte COMPLEX_CONTEXT = 21;
    public static final byte HANGUL = 22;

    public static final int FLAG_EXTENDED_PICTOGRAPHIC = 1;
    public static final int FLAG_EMOJI_PRESENTATION = 2;

    private static final byte[] BMP = new byte[65536];
    private static final byte[] BMP_FLAGS = new byte[65536];

    private static final int[] EXTENDED_PICTOGRAPHIC = {
        0x00A9, 0x00A9, 0x00AE, 0x00AE, 0x203C, 0x203C, 0x2049, 0x2049, 0x2122, 0x2122, 0x2139, 0x2139,
        0x2194, 0x2199, 0x21A9, 0x21AA, 0x231A, 0x231B, 0x2328, 0x2328, 0x2388, 0x2388, 0x23CF, 0x23CF,
        0x23E9, 0x23F3, 0x23F8, 0x23FA, 0x24C2, 0x24C2, 0x25AA, 0x25AB, 0x25B6, 0x25B6, 0x25C0, 0x25C0,
        0x25FB, 0x25FE, 0x2600, 0x2605, 0x2607, 0x2612, 0x2614, 0x2685, 0x2690, 0x2705, 0x2708, 0x2712,
        0x2714, 0x2714, 0x2716, 0x2716, 0x271D, 0x271D, 0x2721, 0x2721, 0x2728, 0x2728, 0x2733, 0x2734,
        0x2744, 0x2744, 0x2747, 0x2747, 0x274C, 0x274C, 0x274E, 0x274E, 0x2753, 0x2755, 0x2757, 0x2757,
        0x2763, 0x2767, 0x2795, 0x2797, 0x27A1, 0x27A1, 0x27B0, 0x27B0, 0x27BF, 0x27BF, 0x2934, 0x2935,
        0x2B05, 0x2B07, 0x2B1B, 0x2B1C, 0x2B50, 0x2B50, 0x2B55, 0x2B55, 0x3030, 0x3030, 0x303D, 0x303D,
        0x3297, 0x3297, 0x3299, 0x3299, 0x1F000, 0x1F0FF, 0x1F10D, 0x1F10F, 0x1F12F, 0x1F12F,
        0x1F16C, 0x1F171, 0x1F17E, 0x1F17F, 0x1F18E, 0x1F18E, 0x1F191, 0x1F19A, 0x1F1AD, 0x1F1E5,
        0x1F201, 0x1F20F, 0x1F21A, 0x1F21A, 0x1F22F, 0x1F22F, 0x1F232, 0x1F23A, 0x1F23C, 0x1F23F,
        0x1F249, 0x1F3FA, 0x1F400, 0x1F53D, 0x1F546, 0x1F64F, 0x1F680, 0x1F6FF, 0x1F774, 0x1F77F,
        0x1F7D5, 0x1F7FF, 0x1F80C, 0x1F80F, 0x1F848, 0x1F84F, 0x1F85A, 0x1F85F, 0x1F888, 0x1F88F,
        0x1F8AE, 0x1F8FF, 0x1F90C, 0x1F93A, 0x1F93C, 0x1F945, 0x1F947, 0x1FAFF, 0x1FC00, 0x1FFFD
    };

    private static final int[] EMOJI_PRESENTATION = {
        0x231A, 0x231B, 0x23E9, 0x23EC, 0x23F0, 0x23F0, 0x23F3, 0x23F3, 0x25FD, 0x25FE, 0x2614, 0x2615,
        0x2648, 0x2653, 0x267F, 0x267F, 0x2693, 0x2693, 0x26A1, 0x26A1, 0x26AA, 0x26AB, 0x26BD, 0x26BE,
        0x26C4, 0x26C5, 0x26CE, 0x26CE, 0x26D4, 0x26D4, 0x26EA, 0x26EA, 0x26F2, 0x26F3, 0x26F5, 0x26F5,
        0x26FA, 0x26FA, 0x26FD, 0x26FD, 0x2705, 0x2705, 0x270A, 0x270B, 0x2728, 0x2728, 0x274C, 0x274C,
        0x274E, 0x274E, 0x2753, 0x2755, 0x2757, 0x2757, 0x2795, 0x2797, 0x27B0, 0x27B0, 0x27BF, 0x27BF,
        0x2B1B, 0x2B1C, 0x2B50, 0x2B50, 0x2B55, 0x2B55, 0x1F004, 0x1F004, 0x1F0CF, 0x1F0CF, 0x1F18E, 0x1F18E,
        0x1F191, 0x1F19A, 0x1F1E6, 0x1F1FF, 0x1F201, 0x1F201, 0x1F21A, 0x1F21A, 0x1F22F, 0x1F22F,
        0x1F232, 0x1F236, 0x1F238, 0x1F23A, 0x1F250, 0x1F251, 0x1F300, 0x1F320, 0x1F32D, 0x1F335,
        0x1F337, 0x1F37C, 0x1F37E, 0x1F393, 0x1F3A0, 0x1F3CA, 0x1F3CF, 0x1F3D3, 0x1F3E0, 0x1F3F0,
        0x1F3F4, 0x1F3F4, 0x1F3F8, 0x1F43E, 0x1F440, 0x1F440, 0x1F442, 0x1F4FC, 0x1F4FF, 0x1F53D,
        0x1F54B, 0x1F54E, 0x1F550, 0x1F567, 0x1F57A, 0x1F57A, 0x1F595, 0x1F596, 0x1F5A4, 0x1F5A4,
        0x1F5FB, 0x1F64F, 0x1F680, 0x1F6C5, 0x1F6CC, 0x1F6CC, 0x1F6D0, 0x1F6D2, 0x1F6D5, 0x1F6D7,
        0x1F6DC, 0x1F6DF, 0x1F6EB, 0x1F6EC, 0x1F6F4, 0x1F6FC, 0x1F7E0, 0x1F7EB, 0x1F7F0, 0x1F7F0,
        0x1F90C, 0x1F93A, 0x1F93C, 0x1F945, 0x1F947, 0x1F9FF, 0x1FA70, 0x1FAFF
    };

    static {
        for (int c = 0; c < 65536; c++) {
            BMP[c] = compute(c);
            BMP_FLAGS[c] = (byte) computeFlags(c);
        }
    }

    private WordBreak() {
    }

    public static byte property(int cp) {
        if (cp < 65536) {
            return BMP[cp];
        }
        return compute(cp);
    }

    public static int flags(int cp) {
        if (cp < 65536) {
            return BMP_FLAGS[cp];
        }
        return computeFlags(cp);
    }

    public static boolean isExtendedPictographic(int cp) {
        return (flags(cp) & FLAG_EXTENDED_PICTOGRAPHIC) != 0;
    }

    public static boolean isEmojiPresentation(int cp) {
        return (flags(cp) & FLAG_EMOJI_PRESENTATION) != 0;
    }

    private static int computeFlags(int cp) {
        int f = 0;
        if (inRanges(EXTENDED_PICTOGRAPHIC, cp)) {
            f |= FLAG_EXTENDED_PICTOGRAPHIC;
        }
        if (inRanges(EMOJI_PRESENTATION, cp)) {
            f |= FLAG_EMOJI_PRESENTATION;
        }
        return f;
    }

    private static boolean inRanges(int[] ranges, int cp) {
        int lo = 0;
        int hi = ranges.length / 2 - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (cp < ranges[mid * 2]) {
                hi = mid - 1;
            } else if (cp > ranges[mid * 2 + 1]) {
                lo = mid + 1;
            } else {
                return true;
            }
        }
        return false;
    }

    private static byte compute(int cp) {
        switch (cp) {
            case 0x0D:
                return CR;
            case 0x0A:
                return LF;
            case 0x0B: case 0x0C: case 0x85: case 0x2028: case 0x2029:
                return NEWLINE;
            case 0x200D:
                return ZWJ;
            case 0x200C:
                return EXTEND;
            case 0x27:
                return SINGLE_QUOTE;
            case 0x22:
                return DOUBLE_QUOTE;
            case 0x2E: case 0x2018: case 0x2019: case 0x2024: case 0xFE52: case 0xFF07: case 0xFF0E:
                return MID_NUM_LET;
            case 0x3A: case 0xB7: case 0x387: case 0x55F: case 0x5F4: case 0x2027: case 0xFE13: case 0xFE55: case 0xFF1A:
                return MID_LETTER;
            case 0x2C: case 0x3B: case 0x37E: case 0x589: case 0x60C: case 0x60D: case 0x66C: case 0x7F8: case 0x2044:
            case 0xFE10: case 0xFE14: case 0xFE50: case 0xFE54: case 0xFF0C: case 0xFF1B:
                return MID_NUM;
            case 0x202F:
                return EXTEND_NUM_LET;
            case 0x066B:
                return NUMERIC;
            case 0x3031: case 0x3032: case 0x3033: case 0x3034: case 0x3035: case 0x309B: case 0x309C: case 0x30A0: case 0x30FC: case 0xFF70:
                return KATAKANA;
            case 0xFF9E: case 0xFF9F:
                return EXTEND;
            default:
                break;
        }
        if (cp >= 0x1F1E6 && cp <= 0x1F1FF) {
            return REGIONAL_INDICATOR;
        }
        if (cp >= 0x1F3FB && cp <= 0x1F3FF) {
            return EXTEND;
        }
        if (cp >= 0xE0020 && cp <= 0xE007F) {
            return EXTEND;
        }
        int type = Character.getType(cp);
        switch (type) {
            case Character.NON_SPACING_MARK:
            case Character.ENCLOSING_MARK:
            case Character.COMBINING_SPACING_MARK:
                return EXTEND;
            case Character.FORMAT:
                if (cp == 0x200B) {
                    return OTHER;
                }
                return FORMAT;
            case Character.CONNECTOR_PUNCTUATION:
                return EXTEND_NUM_LET;
            case Character.SPACE_SEPARATOR:
                if (cp == 0xA0 || cp == 0x2007) {
                    return OTHER;
                }
                return WSEG_SPACE;
            case Character.DECIMAL_DIGIT_NUMBER:
                return NUMERIC;
            default:
                break;
        }
        Character.UnicodeScript script;
        try {
            script = Character.UnicodeScript.of(cp);
        } catch (IllegalArgumentException e) {
            return OTHER;
        }
        switch (script) {
            case HAN:
                return IDEOGRAPHIC;
            case HIRAGANA:
                return HIRAGANA;
            case KATAKANA:
                return KATAKANA;
            case THAI: case LAO: case MYANMAR: case KHMER: case TAI_LE: case NEW_TAI_LUE: case TAI_THAM: case TAI_VIET: case AHOM:
                if (Character.isLetter(cp) || type == Character.MODIFIER_LETTER || type == Character.OTHER_PUNCTUATION && Character.isAlphabetic(cp)) {
                    return COMPLEX_CONTEXT;
                }
                if (Character.isAlphabetic(cp)) {
                    return COMPLEX_CONTEXT;
                }
                return OTHER;
            case HEBREW:
                if (type == Character.OTHER_LETTER) {
                    return HEBREW_LETTER;
                }
                break;
            case HANGUL:
                if (Character.isAlphabetic(cp)) {
                    return HANGUL;
                }
                break;
            default:
                break;
        }
        if (Character.isIdeographic(cp)) {
            return IDEOGRAPHIC;
        }
        if (Character.isAlphabetic(cp)) {
            return ALETTER;
        }
        if ((cp >= 0x02C2 && cp <= 0x02C5) || (cp >= 0x02D2 && cp <= 0x02D7) || (cp >= 0x02DE && cp <= 0x02DF)
            || (cp >= 0x02E5 && cp <= 0x02EB) || cp == 0x02ED || (cp >= 0x02EF && cp <= 0x02FF)
            || (cp >= 0x055A && cp <= 0x055C) || cp == 0x055E || cp == 0x058A || cp == 0x05F3
            || (cp >= 0xA708 && cp <= 0xA716) || (cp >= 0xA720 && cp <= 0xA721) || (cp >= 0xA789 && cp <= 0xA78A)
            || cp == 0xAB5B || (cp >= 0xAB6A && cp <= 0xAB6B)) {
            return ALETTER;
        }
        return OTHER;
    }
}
