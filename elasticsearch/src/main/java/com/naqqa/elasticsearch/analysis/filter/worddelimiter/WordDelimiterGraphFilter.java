package com.naqqa.elasticsearch.analysis.filter.worddelimiter;

import com.naqqa.elasticsearch.analysis.Token;
import com.naqqa.elasticsearch.analysis.TokenFilter;
import com.naqqa.elasticsearch.analysis.TokenStream;

import java.util.Arrays;
import java.util.Set;

public final class WordDelimiterGraphFilter extends TokenFilter {

    public static final int GENERATE_WORD_PARTS = 1;
    public static final int GENERATE_NUMBER_PARTS = 2;
    public static final int CATENATE_WORDS = 4;
    public static final int CATENATE_NUMBERS = 8;
    public static final int CATENATE_ALL = 16;
    public static final int PRESERVE_ORIGINAL = 32;
    public static final int SPLIT_ON_CASE_CHANGE = 64;
    public static final int SPLIT_ON_NUMERICS = 128;
    public static final int STEM_ENGLISH_POSSESSIVE = 256;
    public static final int IGNORE_KEYWORDS = 512;

    private static final int ALL_FLAGS = GENERATE_WORD_PARTS | GENERATE_NUMBER_PARTS | CATENATE_WORDS | CATENATE_NUMBERS
        | CATENATE_ALL | PRESERVE_ORIGINAL | SPLIT_ON_CASE_CHANGE | SPLIT_ON_NUMERICS | STEM_ENGLISH_POSSESSIVE | IGNORE_KEYWORDS;

    private final Set<String> protWords;
    private final int flags;

    private int[] bufferedParts = new int[16];
    private int bufferedLen;
    private int bufferedPos;
    private char[][] bufferedTermParts = new char[4][];

    private final WordDelimiterIterator iterator;
    private final Concatenation concat = new Concatenation();
    private final boolean adjustInternalOffsets;
    private int lastConcatCount;
    private final Concatenation concatAll = new Concatenation();
    private int accumPosInc;

    private char[] savedTermBuffer = new char[16];
    private int savedTermLength;
    private int savedStartOffset;
    private int savedEndOffset;
    private Token savedState;
    private int lastStartOffset;
    private boolean adjustingOffsets;

    private int wordPos;

    public WordDelimiterGraphFilter(TokenStream in, boolean adjustInternalOffsets, byte[] charTypeTable, int configurationFlags, Set<String> protWords) {
        super(in);
        if ((configurationFlags & ~ALL_FLAGS) != 0) {
            throw new IllegalArgumentException("flags contains unrecognized flag: " + configurationFlags);
        }
        this.flags = configurationFlags;
        this.protWords = protWords;
        this.iterator = new WordDelimiterIterator(
            charTypeTable == null ? WordDelimiterIterator.DEFAULT_WORD_DELIM_TABLE : charTypeTable,
            has(SPLIT_ON_CASE_CHANGE),
            has(SPLIT_ON_NUMERICS),
            has(STEM_ENGLISH_POSSESSIVE));
        this.adjustInternalOffsets = adjustInternalOffsets;
    }

    public WordDelimiterGraphFilter(TokenStream in, int configurationFlags, Set<String> protWords) {
        this(in, false, WordDelimiterIterator.DEFAULT_WORD_DELIM_TABLE, configurationFlags, protWords);
    }

    private void bufferWordParts() {
        saveState();
        adjustingOffsets = adjustInternalOffsets && savedEndOffset - savedStartOffset == savedTermLength;
        bufferedLen = 0;
        lastConcatCount = 0;
        wordPos = 0;
        if (has(PRESERVE_ORIGINAL)) {
            buffer(0, 1, 0, savedTermLength);
        }
        if (iterator.isSingleWord()) {
            buffer(wordPos, wordPos + 1, iterator.current, iterator.end);
            wordPos++;
            iterator.next();
        } else {
            while (iterator.end != WordDelimiterIterator.DONE) {
                int wordType = iterator.type();
                if (concat.isNotEmpty() && (concat.type & wordType) == 0) {
                    flushConcatenation(concat);
                }
                if (shouldConcatenate(wordType)) {
                    concatenate(concat);
                }
                if (has(CATENATE_ALL)) {
                    concatenate(concatAll);
                }
                if (shouldGenerateParts(wordType)) {
                    buffer(wordPos, wordPos + 1, iterator.current, iterator.end);
                    wordPos++;
                }
                iterator.next();
            }
            if (concat.isNotEmpty()) {
                flushConcatenation(concat);
            }
            if (concatAll.isNotEmpty()) {
                if (concatAll.subwordCount > lastConcatCount) {
                    if (wordPos == concatAll.startPos) {
                        wordPos++;
                    }
                    concatAll.write();
                }
                concatAll.clear();
            }
        }
        if (has(PRESERVE_ORIGINAL)) {
            if (wordPos == 0) {
                wordPos++;
            }
            bufferedParts[1] = wordPos;
        }
        sortParts(has(PRESERVE_ORIGINAL) ? 1 : 0, bufferedLen);
        wordPos = 0;
        bufferedPos = 0;
    }

    @Override
    public boolean incrementToken() {
        while (true) {
            if (savedState == null) {
                if (!input.incrementToken()) {
                    return false;
                }
                if (has(IGNORE_KEYWORDS) && token.isKeyword()) {
                    return true;
                }
                int termLength = token.length();
                char[] termBuffer = token.buffer();
                accumPosInc += token.positionIncrement();
                iterator.setText(termBuffer, termLength);
                iterator.next();
                if ((iterator.current == 0 && iterator.end == termLength)
                    || (protWords != null && protWords.contains(new String(termBuffer, 0, termLength)))) {
                    token.setPositionIncrement(accumPosInc);
                    accumPosInc = 0;
                    return true;
                }
                if (iterator.end == WordDelimiterIterator.DONE) {
                    if (!has(PRESERVE_ORIGINAL)) {
                        continue;
                    } else {
                        accumPosInc = 0;
                        return true;
                    }
                }
                bufferWordParts();
            }
            if (bufferedPos < bufferedLen) {
                token.clear();
                token.copyFrom(savedState);
                char[] termPart = bufferedTermParts[bufferedPos];
                int startPos = bufferedParts[4 * bufferedPos];
                int endPos = bufferedParts[4 * bufferedPos + 1];
                int startPart = bufferedParts[4 * bufferedPos + 2];
                int endPart = bufferedParts[4 * bufferedPos + 3];
                bufferedPos++;
                int startOffset;
                int endOffset;
                if (!adjustingOffsets) {
                    startOffset = savedStartOffset;
                    endOffset = savedEndOffset;
                } else {
                    startOffset = savedStartOffset + startPart;
                    endOffset = savedStartOffset + endPart;
                }
                startOffset = Math.max(startOffset, lastStartOffset);
                endOffset = Math.max(endOffset, lastStartOffset);
                token.setOffset(startOffset, endOffset);
                lastStartOffset = startOffset;
                if (termPart == null) {
                    token.setTerm(savedTermBuffer, startPart, endPart - startPart);
                } else {
                    token.setTerm(termPart, 0, termPart.length);
                }
                token.setPositionIncrement(accumPosInc + startPos - wordPos);
                accumPosInc = 0;
                token.setPositionLength(endPos - startPos);
                wordPos = startPos;
                return true;
            }
            savedState = null;
        }
    }

    @Override
    public void reset() {
        super.reset();
        accumPosInc = 0;
        savedState = null;
        lastStartOffset = 0;
        bufferedLen = 0;
        bufferedPos = 0;
        concat.clear();
        concatAll.clear();
    }

    private int compareParts(int i, int j) {
        int cmp = Integer.compare(bufferedParts[4 * i + 2], bufferedParts[4 * j + 2]);
        if (cmp != 0) {
            return cmp;
        }
        return Integer.compare(bufferedParts[4 * j + 3], bufferedParts[4 * i + 3]);
    }

    private void sortParts(int from, int to) {
        int n = to - from;
        if (n < 2) {
            return;
        }
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) {
            order[i] = from + i;
        }
        Arrays.sort(order, this::compareParts);
        int[] parts = Arrays.copyOfRange(bufferedParts, 4 * from, 4 * to);
        char[][] terms = Arrays.copyOfRange(bufferedTermParts, from, to);
        for (int i = 0; i < n; i++) {
            int src = order[i] - from;
            System.arraycopy(parts, 4 * src, bufferedParts, 4 * (from + i), 4);
            bufferedTermParts[from + i] = terms[src];
        }
    }

    private void buffer(int startPos, int endPos, int startPart, int endPart) {
        buffer(null, startPos, endPos, startPart, endPart);
    }

    private void buffer(char[] termPart, int startPos, int endPos, int startPart, int endPart) {
        if ((bufferedLen + 1) * 4 > bufferedParts.length) {
            bufferedParts = Arrays.copyOf(bufferedParts, Math.max((bufferedLen + 1) * 4, bufferedParts.length * 2));
        }
        if (bufferedTermParts.length == bufferedLen) {
            bufferedTermParts = Arrays.copyOf(bufferedTermParts, Math.max(bufferedLen + 1, bufferedTermParts.length * 2));
        }
        bufferedTermParts[bufferedLen] = termPart;
        bufferedParts[bufferedLen * 4] = startPos;
        bufferedParts[bufferedLen * 4 + 1] = endPos;
        bufferedParts[bufferedLen * 4 + 2] = startPart;
        bufferedParts[bufferedLen * 4 + 3] = endPart;
        bufferedLen++;
    }

    private void saveState() {
        savedTermLength = token.length();
        savedStartOffset = token.startOffset();
        savedEndOffset = token.endOffset();
        savedState = token.copy();
        if (savedTermBuffer.length < savedTermLength) {
            savedTermBuffer = new char[savedTermLength + (savedTermLength >> 3) + 8];
        }
        System.arraycopy(token.buffer(), 0, savedTermBuffer, 0, savedTermLength);
    }

    private void flushConcatenation(Concatenation concatenation) {
        if (wordPos == concatenation.startPos) {
            wordPos++;
        }
        lastConcatCount = concatenation.subwordCount;
        if (concatenation.subwordCount != 1 || !shouldGenerateParts(concatenation.type)) {
            concatenation.write();
        }
        concatenation.clear();
    }

    private boolean shouldConcatenate(int wordType) {
        return (has(CATENATE_WORDS) && WordDelimiterIterator.isAlpha(wordType))
            || (has(CATENATE_NUMBERS) && WordDelimiterIterator.isDigit(wordType));
    }

    private boolean shouldGenerateParts(int wordType) {
        return (has(GENERATE_WORD_PARTS) && WordDelimiterIterator.isAlpha(wordType))
            || (has(GENERATE_NUMBER_PARTS) && WordDelimiterIterator.isDigit(wordType));
    }

    private void concatenate(Concatenation concatenation) {
        if (concatenation.isEmpty()) {
            concatenation.type = iterator.type();
            concatenation.startPart = iterator.current;
            concatenation.startPos = wordPos;
        }
        concatenation.append(savedTermBuffer, iterator.current, iterator.end - iterator.current);
        concatenation.endPart = iterator.end;
    }

    private boolean has(int flag) {
        return (flags & flag) != 0;
    }

    private final class Concatenation {
        final StringBuilder buffer = new StringBuilder();
        int startPart;
        int endPart;
        int startPos;
        int type;
        int subwordCount;

        void append(char[] text, int offset, int length) {
            buffer.append(text, offset, length);
            subwordCount++;
        }

        void write() {
            char[] termPart = new char[buffer.length()];
            buffer.getChars(0, buffer.length(), termPart, 0);
            buffer(termPart, startPos, wordPos, startPart, endPart);
        }

        boolean isEmpty() {
            return buffer.length() == 0;
        }

        boolean isNotEmpty() {
            return !isEmpty();
        }

        void clear() {
            buffer.setLength(0);
            startPart = endPart = type = subwordCount = 0;
        }
    }

    public static String flagsToString(int flags) {
        StringBuilder b = new StringBuilder();
        String[] names = {"GENERATE_WORD_PARTS", "GENERATE_NUMBER_PARTS", "CATENATE_WORDS", "CATENATE_NUMBERS", "CATENATE_ALL",
            "PRESERVE_ORIGINAL", "SPLIT_ON_CASE_CHANGE", "SPLIT_ON_NUMERICS", "STEM_ENGLISH_POSSESSIVE"};
        for (int i = 0; i < names.length; i++) {
            if ((flags & (1 << i)) != 0) {
                if (b.length() > 0) {
                    b.append(" | ");
                }
                b.append(names[i]);
            }
        }
        return b.toString();
    }

    @Override
    public String toString() {
        return "WordDelimiterGraphFilter(flags=" + flagsToString(flags) + ')';
    }
}
