package com.naqqa.elasticsearch.search.highlight;

import java.text.BreakIterator;
import java.util.Locale;

public enum BoundaryScanner {

    CHARS,
    WORD,
    SENTENCE;

    public int precedingBoundary(String text, int pos) {
        int clamped = Math.max(0, Math.min(pos, text.length()));
        if (this == CHARS || text.isEmpty()) {
            return clamped;
        }
        BreakIterator it = newIterator();
        it.setText(text);
        int b = it.preceding(clamped);
        return b == BreakIterator.DONE ? 0 : b;
    }

    public int followingBoundary(String text, int pos) {
        int clamped = Math.max(0, Math.min(pos, text.length()));
        if (this == CHARS || text.isEmpty()) {
            return clamped;
        }
        if (clamped >= text.length()) {
            return text.length();
        }
        BreakIterator it = newIterator();
        it.setText(text);
        int b = it.following(clamped);
        return b == BreakIterator.DONE ? text.length() : b;
    }

    private BreakIterator newIterator() {
        return this == SENTENCE ? BreakIterator.getSentenceInstance(Locale.ROOT) : BreakIterator.getWordInstance(Locale.ROOT);
    }
}
