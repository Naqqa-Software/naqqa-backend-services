package com.naqqa.elasticsearch.analysis.filter.worddelimiter;

public final class WordDelimiterIterator {

    public static final int LOWER = 0x01;
    public static final int UPPER = 0x02;
    public static final int DIGIT = 0x04;
    public static final int SUBWORD_DELIM = 0x08;
    public static final int ALPHA = 0x03;
    public static final int ALPHANUM = 0x07;

    public static final int DONE = -1;

    public static final byte[] DEFAULT_WORD_DELIM_TABLE;

    static {
        byte[] tab = new byte[256];
        for (int i = 0; i < 256; i++) {
            byte code = 0;
            if (Character.isLowerCase(i)) {
                code |= LOWER;
            } else if (Character.isUpperCase(i)) {
                code |= UPPER;
            } else if (Character.isDigit(i)) {
                code |= DIGIT;
            }
            if (code == 0) {
                code = SUBWORD_DELIM;
            }
            tab[i] = code;
        }
        DEFAULT_WORD_DELIM_TABLE = tab;
    }

    char[] text;
    int length;
    int startBounds;
    int endBounds;
    int current;
    int end;

    private boolean hasFinalPossessive;

    final boolean splitOnCaseChange;
    final boolean splitOnNumerics;
    final boolean stemEnglishPossessive;

    private final byte[] charTypeTable;

    private boolean skipPossessive;

    public WordDelimiterIterator(byte[] charTypeTable, boolean splitOnCaseChange, boolean splitOnNumerics, boolean stemEnglishPossessive) {
        this.charTypeTable = charTypeTable == null ? DEFAULT_WORD_DELIM_TABLE : charTypeTable;
        this.splitOnCaseChange = splitOnCaseChange;
        this.splitOnNumerics = splitOnNumerics;
        this.stemEnglishPossessive = stemEnglishPossessive;
    }

    public int current() {
        return current;
    }

    public int end() {
        return end;
    }

    public int next() {
        current = end;
        if (current == DONE) {
            return DONE;
        }
        if (skipPossessive) {
            current += 2;
            skipPossessive = false;
        }
        int lastType = 0;
        while (current < endBounds && isSubwordDelim(lastType = charType(text[current]))) {
            current++;
        }
        if (current >= endBounds) {
            return end = DONE;
        }
        for (end = current + 1; end < endBounds; end++) {
            int type = charType(text[end]);
            if (isBreak(lastType, type)) {
                break;
            }
            lastType = type;
        }
        if (end < endBounds - 1 && endsWithPossessive(end + 2)) {
            skipPossessive = true;
        }
        return end;
    }

    public int type() {
        if (end == DONE) {
            return 0;
        }
        int type = charType(text[current]);
        return switch (type) {
            case LOWER, UPPER -> ALPHA;
            default -> type;
        };
    }

    public void setText(char[] text, int length) {
        this.text = text;
        this.length = this.endBounds = length;
        current = startBounds = end = 0;
        skipPossessive = hasFinalPossessive = false;
        setBounds();
    }

    private boolean isBreak(int lastType, int type) {
        if ((type & lastType) != 0) {
            return false;
        }
        if (!splitOnCaseChange && isAlpha(lastType) && isAlpha(type)) {
            return false;
        } else if (isUpper(lastType) && isAlpha(type)) {
            return false;
        } else if (!splitOnNumerics && ((isAlpha(lastType) && isDigit(type)) || (isDigit(lastType) && isAlpha(type)))) {
            return false;
        }
        return true;
    }

    public boolean isSingleWord() {
        if (hasFinalPossessive) {
            return current == startBounds && end == endBounds - 2;
        } else {
            return current == startBounds && end == endBounds;
        }
    }

    private void setBounds() {
        while (startBounds < length && isSubwordDelim(charType(text[startBounds]))) {
            startBounds++;
        }
        while (endBounds > startBounds && isSubwordDelim(charType(text[endBounds - 1]))) {
            endBounds--;
        }
        if (endsWithPossessive(endBounds)) {
            hasFinalPossessive = true;
        }
        current = startBounds;
    }

    private boolean endsWithPossessive(int pos) {
        return stemEnglishPossessive
            && pos > 2
            && text[pos - 2] == '\''
            && (text[pos - 1] == 's' || text[pos - 1] == 'S')
            && isAlpha(charType(text[pos - 3]))
            && (pos == endBounds || isSubwordDelim(charType(text[pos])));
    }

    private int charType(int ch) {
        if (ch < charTypeTable.length) {
            return charTypeTable[ch];
        }
        return getType(ch);
    }

    public static byte getType(int ch) {
        switch (Character.getType(ch)) {
            case Character.UPPERCASE_LETTER:
                return UPPER;
            case Character.LOWERCASE_LETTER:
                return LOWER;
            case Character.TITLECASE_LETTER:
            case Character.MODIFIER_LETTER:
            case Character.OTHER_LETTER:
            case Character.NON_SPACING_MARK:
            case Character.ENCLOSING_MARK:
            case Character.COMBINING_SPACING_MARK:
                return ALPHA;
            case Character.DECIMAL_DIGIT_NUMBER:
            case Character.LETTER_NUMBER:
            case Character.OTHER_NUMBER:
                return DIGIT;
            case Character.SURROGATE:
                return ALPHA | DIGIT;
            default:
                return SUBWORD_DELIM;
        }
    }

    public static boolean isAlpha(int type) {
        return (type & ALPHA) != 0;
    }

    public static boolean isDigit(int type) {
        return (type & DIGIT) != 0;
    }

    public static boolean isSubwordDelim(int type) {
        return (type & SUBWORD_DELIM) != 0;
    }

    public static boolean isUpper(int type) {
        return (type & UPPER) != 0;
    }
}
