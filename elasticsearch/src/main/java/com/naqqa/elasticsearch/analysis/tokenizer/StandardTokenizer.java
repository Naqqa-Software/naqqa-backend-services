package com.naqqa.elasticsearch.analysis.tokenizer;

import com.naqqa.elasticsearch.analysis.Tokenizer;

public class StandardTokenizer extends Tokenizer {

    public static final String ALPHANUM = "<ALPHANUM>";
    public static final String NUM = "<NUM>";
    public static final String SOUTHEAST_ASIAN = "<SOUTHEAST_ASIAN>";
    public static final String IDEOGRAPHIC = "<IDEOGRAPHIC>";
    public static final String HIRAGANA = "<HIRAGANA>";
    public static final String KATAKANA = "<KATAKANA>";
    public static final String HANGUL = "<HANGUL>";
    public static final String EMOJI = "<EMOJI>";

    public static final int DEFAULT_MAX_TOKEN_LENGTH = 255;
    public static final int MAX_TOKEN_LENGTH_LIMIT = 1024 * 1024;

    private int maxTokenLength;
    private int pos;
    private int skippedPositions;
    private final String[] specialType = new String[1];

    public StandardTokenizer() {
        this(DEFAULT_MAX_TOKEN_LENGTH);
    }

    public StandardTokenizer(int maxTokenLength) {
        setMaxTokenLength(maxTokenLength);
    }

    public void setMaxTokenLength(int length) {
        if (length < 1) {
            throw new IllegalArgumentException("maxTokenLength must be greater than zero");
        }
        if (length > MAX_TOKEN_LENGTH_LIMIT) {
            throw new IllegalArgumentException("maxTokenLength may not exceed " + MAX_TOKEN_LENGTH_LIMIT);
        }
        this.maxTokenLength = length;
    }

    public int getMaxTokenLength() {
        return maxTokenLength;
    }

    @Override
    public void reset() {
        super.reset();
        pos = 0;
        skippedPositions = 0;
    }

    protected int matchSpecial(CharSequence text, int start, int end, String[] typeOut) {
        return -1;
    }

    protected boolean hasSpecialMatchers() {
        return false;
    }

    @Override
    public final boolean incrementToken() {
        CharSequence text = input;
        int len = text.length();
        while (pos < len) {
            int start = pos;
            int segEnd = nextBoundary(text, start, len);
            if (hasSpecialMatchers()) {
                int special = matchSpecial(text, start, len, specialType);
                if (special > start && special >= segEnd) {
                    int tokenEnd = special;
                    if (tokenEnd - start > maxTokenLength) {
                        tokenEnd = clip(text, start, start + maxTokenLength);
                    }
                    pos = tokenEnd;
                    emit(text, start, tokenEnd, specialType[0]);
                    return true;
                }
            }
            String type = classify(text, start, segEnd);
            if (type == null) {
                pos = segEnd;
                continue;
            }
            int tokenEnd = segEnd;
            if (tokenEnd - start > maxTokenLength) {
                tokenEnd = clip(text, start, start + maxTokenLength);
                type = classify(text, start, tokenEnd);
                if (type == null) {
                    type = ALPHANUM;
                }
            }
            pos = tokenEnd;
            emit(text, start, tokenEnd, type);
            return true;
        }
        return false;
    }

    private static int clip(CharSequence text, int start, int limit) {
        if (limit > start + 1 && Character.isHighSurrogate(text.charAt(limit - 1)) && limit < text.length()
            && Character.isLowSurrogate(text.charAt(limit))) {
            return limit - 1;
        }
        return limit;
    }

    private void emit(CharSequence text, int start, int end, String type) {
        token.clear();
        token.setPositionIncrement(skippedPositions + 1);
        skippedPositions = 0;
        token.setTerm(text, start, end);
        token.setOffset(correctOffset(start), correctOffset(end));
        token.setType(type);
    }

    @Override
    public void end() {
        super.end();
        token.setPositionIncrement(token.positionIncrement() + skippedPositions);
    }

    private static boolean ignorable(byte p) {
        return p == WordBreak.EXTEND || p == WordBreak.FORMAT || p == WordBreak.ZWJ;
    }

    private static boolean ahLetter(byte p) {
        return p == WordBreak.ALETTER || p == WordBreak.HEBREW_LETTER || p == WordBreak.HANGUL;
    }

    private static boolean midLetterQ(byte p) {
        return p == WordBreak.MID_LETTER || p == WordBreak.MID_NUM_LET || p == WordBreak.SINGLE_QUOTE;
    }

    private static boolean midNumQ(byte p) {
        return p == WordBreak.MID_NUM || p == WordBreak.MID_NUM_LET || p == WordBreak.SINGLE_QUOTE;
    }

    private static byte nextEffective(CharSequence text, int i, int end) {
        while (i < end) {
            int cp = Character.codePointAt(text, i);
            byte p = WordBreak.property(cp);
            if (!ignorable(p)) {
                return p;
            }
            i += Character.charCount(cp);
        }
        return -1;
    }

    public static int nextBoundary(CharSequence text, int start, int end) {
        int i = start;
        int cp = Character.codePointAt(text, i);
        byte p0 = WordBreak.property(cp);
        i += Character.charCount(cp);
        byte raw = p0;
        byte eff = p0;
        byte eff2 = -1;
        int riCount = p0 == WordBreak.REGIONAL_INDICATOR ? 1 : 0;
        while (i < end) {
            cp = Character.codePointAt(text, i);
            byte p = WordBreak.property(cp);
            int n = Character.charCount(cp);
            boolean brk;
            if (raw == WordBreak.CR && p == WordBreak.LF) {
                brk = false;
            } else if (raw == WordBreak.CR || raw == WordBreak.LF || raw == WordBreak.NEWLINE) {
                brk = true;
            } else if (p == WordBreak.CR || p == WordBreak.LF || p == WordBreak.NEWLINE) {
                brk = true;
            } else if (raw == WordBreak.ZWJ && WordBreak.isExtendedPictographic(cp)) {
                brk = false;
            } else if (raw == WordBreak.WSEG_SPACE && p == WordBreak.WSEG_SPACE) {
                brk = false;
            } else if (ignorable(p)) {
                raw = p;
                i += n;
                continue;
            } else {
                brk = decide(text, eff2, eff, p, i + n, end, riCount);
            }
            if (brk) {
                return i;
            }
            raw = p;
            eff2 = eff;
            eff = p;
            riCount = p == WordBreak.REGIONAL_INDICATOR ? riCount + 1 : 0;
            i += n;
        }
        return end;
    }

    private static boolean decide(CharSequence text, byte pp, byte prv, byte cur, int afterCur, int end, int riCount) {
        if (ahLetter(prv) && ahLetter(cur)) {
            return false;
        }
        if (ahLetter(prv) && midLetterQ(cur) && ahLetter(nextEffective(text, afterCur, end))) {
            return false;
        }
        if (ahLetter(pp) && midLetterQ(prv) && ahLetter(cur)) {
            return false;
        }
        if (prv == WordBreak.HEBREW_LETTER && cur == WordBreak.SINGLE_QUOTE) {
            return false;
        }
        if (prv == WordBreak.HEBREW_LETTER && cur == WordBreak.DOUBLE_QUOTE && nextEffective(text, afterCur, end) == WordBreak.HEBREW_LETTER) {
            return false;
        }
        if (pp == WordBreak.HEBREW_LETTER && prv == WordBreak.DOUBLE_QUOTE && cur == WordBreak.HEBREW_LETTER) {
            return false;
        }
        if (prv == WordBreak.NUMERIC && cur == WordBreak.NUMERIC) {
            return false;
        }
        if (ahLetter(prv) && cur == WordBreak.NUMERIC) {
            return false;
        }
        if (prv == WordBreak.NUMERIC && ahLetter(cur)) {
            return false;
        }
        if (pp == WordBreak.NUMERIC && midNumQ(prv) && cur == WordBreak.NUMERIC) {
            return false;
        }
        if (prv == WordBreak.NUMERIC && midNumQ(cur) && nextEffective(text, afterCur, end) == WordBreak.NUMERIC) {
            return false;
        }
        if (prv == WordBreak.KATAKANA && cur == WordBreak.KATAKANA) {
            return false;
        }
        if ((ahLetter(prv) || prv == WordBreak.NUMERIC || prv == WordBreak.KATAKANA || prv == WordBreak.EXTEND_NUM_LET)
            && cur == WordBreak.EXTEND_NUM_LET) {
            return false;
        }
        if (prv == WordBreak.EXTEND_NUM_LET && (ahLetter(cur) || cur == WordBreak.NUMERIC || cur == WordBreak.KATAKANA)) {
            return false;
        }
        if (prv == WordBreak.REGIONAL_INDICATOR && cur == WordBreak.REGIONAL_INDICATOR && (riCount & 1) == 1) {
            return false;
        }
        if (prv == WordBreak.COMPLEX_CONTEXT && cur == WordBreak.COMPLEX_CONTEXT) {
            return false;
        }
        return true;
    }

    public static String classify(CharSequence text, int start, int end) {
        boolean letter = false;
        boolean hangul = false;
        boolean numeric = false;
        boolean katakana = false;
        boolean emoji = false;
        int riCount = 0;
        int prevCp = -1;
        int i = start;
        while (i < end) {
            int cp = Character.codePointAt(text, i);
            byte p = WordBreak.property(cp);
            switch (p) {
                case WordBreak.IDEOGRAPHIC:
                    return IDEOGRAPHIC;
                case WordBreak.HIRAGANA:
                    return HIRAGANA;
                case WordBreak.COMPLEX_CONTEXT:
                    return SOUTHEAST_ASIAN;
                case WordBreak.ALETTER:
                case WordBreak.HEBREW_LETTER:
                    letter = true;
                    break;
                case WordBreak.HANGUL:
                    hangul = true;
                    break;
                case WordBreak.NUMERIC:
                    numeric = true;
                    break;
                case WordBreak.KATAKANA:
                    katakana = true;
                    break;
                case WordBreak.REGIONAL_INDICATOR:
                    riCount++;
                    break;
                default:
                    if (cp == 0xFE0F && prevCp >= 0 && WordBreak.isExtendedPictographic(prevCp)) {
                        emoji = true;
                    } else if (cp == 0x20E3 && prevCp >= 0 && (prevCp == '#' || prevCp == '*' || (prevCp >= '0' && prevCp <= '9') || prevCp == 0xFE0F)) {
                        return EMOJI;
                    } else if (WordBreak.isEmojiPresentation(cp)) {
                        emoji = true;
                    }
                    break;
            }
            prevCp = cp;
            i += Character.charCount(cp);
        }
        if (katakana && !letter && !hangul && !numeric) {
            return KATAKANA;
        }
        if (hangul && !letter && !numeric && !katakana) {
            return HANGUL;
        }
        if (letter || hangul || katakana) {
            return ALPHANUM;
        }
        if (numeric) {
            return NUM;
        }
        if (emoji || riCount > 0) {
            return EMOJI;
        }
        return null;
    }
}
