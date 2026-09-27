package com.naqqa.elasticsearch.analysis.tokenizer;

import java.util.List;

public final class TokenChars {

    private TokenChars() {
    }

    public static NGramTokenizer.CodepointMatcher build(List<String> tokenChars, String customTokenChars) {
        if (tokenChars == null || tokenChars.isEmpty()) {
            return NGramTokenizer.MATCH_ALL;
        }
        boolean letter = false;
        boolean digit = false;
        boolean whitespace = false;
        boolean punctuation = false;
        boolean symbol = false;
        boolean custom = false;
        for (String c : tokenChars) {
            switch (c) {
                case "letter" -> letter = true;
                case "digit" -> digit = true;
                case "whitespace" -> whitespace = true;
                case "punctuation" -> punctuation = true;
                case "symbol" -> symbol = true;
                case "custom" -> custom = true;
                default -> throw new IllegalArgumentException("Unknown token type: '" + c + "'");
            }
        }
        String customSet = customTokenChars == null ? "" : customTokenChars;
        boolean fl = letter;
        boolean fd = digit;
        boolean fw = whitespace;
        boolean fp = punctuation;
        boolean fs = symbol;
        boolean fc = custom;
        return cp -> {
            if (fc && customSet.indexOf(cp) >= 0) {
                return true;
            }
            if (fl && Character.isLetter(cp)) {
                return true;
            }
            if (fd && Character.isDigit(cp)) {
                return true;
            }
            if (fw && Character.isWhitespace(cp)) {
                return true;
            }
            int type = Character.getType(cp);
            if (fp && (type == Character.CONNECTOR_PUNCTUATION || type == Character.DASH_PUNCTUATION
                || type == Character.END_PUNCTUATION || type == Character.FINAL_QUOTE_PUNCTUATION
                || type == Character.INITIAL_QUOTE_PUNCTUATION || type == Character.OTHER_PUNCTUATION
                || type == Character.START_PUNCTUATION)) {
                return true;
            }
            if (fs && (type == Character.CURRENCY_SYMBOL || type == Character.MATH_SYMBOL
                || type == Character.MODIFIER_SYMBOL || type == Character.OTHER_SYMBOL)) {
                return true;
            }
            return false;
        };
    }
}
