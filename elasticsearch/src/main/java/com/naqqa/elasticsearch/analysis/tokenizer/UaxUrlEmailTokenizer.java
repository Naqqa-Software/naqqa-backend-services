package com.naqqa.elasticsearch.analysis.tokenizer;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class UaxUrlEmailTokenizer extends StandardTokenizer {

    public static final String URL = "<URL>";
    public static final String EMAIL = "<EMAIL>";

    private static final Pattern URL_PATTERN = Pattern.compile("(?:[A-Za-z][A-Za-z0-9+.-]*://|www\\.)[^\\s<>\"]+");
    private static final Pattern EMAIL_PATTERN = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");

    public UaxUrlEmailTokenizer() {
        super();
    }

    public UaxUrlEmailTokenizer(int maxTokenLength) {
        super(maxTokenLength);
    }

    @Override
    protected boolean hasSpecialMatchers() {
        return true;
    }

    @Override
    protected int matchSpecial(CharSequence text, int start, int end, String[] typeOut) {
        int urlEnd = match(URL_PATTERN, text, start, end);
        int emailEnd = match(EMAIL_PATTERN, text, start, end);
        if (urlEnd > start && urlEnd >= emailEnd) {
            typeOut[0] = URL;
            return urlEnd;
        }
        if (emailEnd > start) {
            typeOut[0] = EMAIL;
            return emailEnd;
        }
        return -1;
    }

    private static int match(Pattern pattern, CharSequence text, int start, int end) {
        Matcher m = pattern.matcher(text);
        m.region(start, end);
        m.useTransparentBounds(true);
        m.useAnchoringBounds(true);
        if (!m.lookingAt()) {
            return -1;
        }
        int matchEnd = m.end();
        while (matchEnd > start + 1) {
            char c = text.charAt(matchEnd - 1);
            if (c == '.' || c == ',' || c == ';' || c == ':' || c == '!' || c == '?' || c == ')' || c == '\'' || c == '"') {
                matchEnd--;
            } else {
                break;
            }
        }
        return matchEnd;
    }
}
