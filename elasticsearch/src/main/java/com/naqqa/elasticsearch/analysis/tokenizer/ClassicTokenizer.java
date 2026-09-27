package com.naqqa.elasticsearch.analysis.tokenizer;

import com.naqqa.elasticsearch.analysis.Tokenizer;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ClassicTokenizer extends Tokenizer {

    public static final String ALPHANUM = "<ALPHANUM>";
    public static final String APOSTROPHE = "<APOSTROPHE>";
    public static final String ACRONYM = "<ACRONYM>";
    public static final String COMPANY = "<COMPANY>";
    public static final String EMAIL = "<EMAIL>";
    public static final String HOST = "<HOST>";
    public static final String NUM = "<NUM>";
    public static final String CJ = "<CJ>";

    private static final Pattern EMAIL_PATTERN = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final Pattern ACRONYM_PATTERN = Pattern.compile("(?:[A-Za-z]\\.){2,}");
    private static final Pattern HOST_PATTERN = Pattern.compile("[A-Za-z0-9]+(?:[.-][A-Za-z0-9]+)+");
    private static final Pattern COMPANY_PATTERN = Pattern.compile("[A-Za-z]+[&@][A-Za-z]+");
    private static final Pattern NUM_PATTERN = Pattern.compile("[+-]?[0-9]+(?:[.,][0-9]+)*(?:/[0-9]+(?:[.,][0-9]+)*)?");
    private static final Pattern APOSTROPHE_PATTERN = Pattern.compile("[A-Za-z]+(?:'[A-Za-z]+)+");
    private static final Pattern ALPHANUM_PATTERN = Pattern.compile("[A-Za-z0-9_]+");

    private final int maxTokenLength;
    private int pos;

    public ClassicTokenizer() {
        this(255);
    }

    public ClassicTokenizer(int maxTokenLength) {
        this.maxTokenLength = maxTokenLength;
    }

    @Override
    public void reset() {
        super.reset();
        pos = 0;
    }

    @Override
    public boolean incrementToken() {
        CharSequence text = input;
        int len = text.length();
        while (pos < len && Character.isWhitespace(text.charAt(pos))) {
            pos++;
        }
        if (pos >= len) {
            return false;
        }
        int cp = Character.codePointAt(text, pos);
        if (isCjk(cp)) {
            int start = pos;
            pos += Character.charCount(cp);
            emit(text, start, pos, CJ);
            return true;
        }
        int start = pos;
        int end;
        String type;
        if ((end = lookingAt(EMAIL_PATTERN, text, start, len)) > start) {
            type = EMAIL;
        } else if ((end = lookingAt(ACRONYM_PATTERN, text, start, len)) > start) {
            type = ACRONYM;
        } else if ((end = lookingAt(COMPANY_PATTERN, text, start, len)) > start) {
            type = COMPANY;
        } else if ((end = lookingAt(HOST_PATTERN, text, start, len)) > start) {
            type = HOST;
        } else if ((end = lookingAt(NUM_PATTERN, text, start, len)) > start) {
            type = NUM;
        } else if ((end = lookingAt(APOSTROPHE_PATTERN, text, start, len)) > start) {
            type = APOSTROPHE;
        } else if ((end = lookingAt(ALPHANUM_PATTERN, text, start, len)) > start) {
            type = ALPHANUM;
        } else {
            pos = start + Character.charCount(cp);
            return incrementToken();
        }
        if (end - start > maxTokenLength) {
            end = start + maxTokenLength;
        }
        pos = end;
        emit(text, start, end, type);
        return true;
    }

    private void emit(CharSequence text, int start, int end, String type) {
        token.clear();
        token.setTerm(text, start, end);
        token.setOffset(correctOffset(start), correctOffset(end));
        token.setType(type);
    }

    private static boolean isCjk(int cp) {
        int block = Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN
            || Character.UnicodeScript.of(cp) == Character.UnicodeScript.HIRAGANA
            || Character.UnicodeScript.of(cp) == Character.UnicodeScript.KATAKANA
            || Character.UnicodeScript.of(cp) == Character.UnicodeScript.HANGUL ? 1 : 0;
        return block == 1;
    }

    private static int lookingAt(Pattern pattern, CharSequence text, int start, int end) {
        Matcher m = pattern.matcher(text);
        m.region(start, end);
        m.useTransparentBounds(true);
        m.useAnchoringBounds(true);
        return m.lookingAt() ? m.end() : -1;
    }
}
