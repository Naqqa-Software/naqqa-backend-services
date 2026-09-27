package com.naqqa.elasticsearch.analysis.filter.worddelimiter;

import com.naqqa.elasticsearch.analysis.Token;
import com.naqqa.elasticsearch.analysis.TokenFilter;
import com.naqqa.elasticsearch.analysis.TokenStream;

import java.util.Arrays;
import java.util.Set;

public final class WordDelimiterFilter extends TokenFilter {

    public static final int LOWER = 0x01;
    public static final int UPPER = 0x02;
    public static final int DIGIT = 0x04;
    public static final int SUBWORD_DELIM = 0x08;
    public static final int ALPHA = 0x03;
    public static final int ALPHANUM = 0x07;

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

    private final Set<String> protWords;
    private final int flags;

    private final WordDelimiterIterator iterator;
    private final Concatenation concat = new Concatenation();
    private int lastConcatCount;
    private final Concatenation concatAll = new Concatenation();
    private int accumPosInc;

    private char[] savedBuffer = new char[1024];
    private int savedStartOffset;
    private int savedEndOffset;
    private String savedType;
    private boolean hasSavedState;
    private boolean hasIllegalOffsets;
    private boolean hasOutputToken;
    private boolean hasOutputFollowingOriginal;

    private Token[] buffered = new Token[8];
    private int[] startOff = new int[8];
    private int[] posInc = new int[8];
    private int bufferedLen;
    private int bufferedPos;
    private boolean first;

    public WordDelimiterFilter(TokenStream in, byte[] charTypeTable, int configurationFlags, Set<String> protWords) {
        super(in);
        this.flags = configurationFlags;
        this.protWords = protWords;
        this.iterator = new WordDelimiterIterator(
            charTypeTable == null ? WordDelimiterIterator.DEFAULT_WORD_DELIM_TABLE : charTypeTable,
            has(SPLIT_ON_CASE_CHANGE),
            has(SPLIT_ON_NUMERICS),
            has(STEM_ENGLISH_POSSESSIVE));
    }

    public WordDelimiterFilter(TokenStream in, int configurationFlags, Set<String> protWords) {
        this(in, WordDelimiterIterator.DEFAULT_WORD_DELIM_TABLE, configurationFlags, protWords);
    }

    @Override
    public boolean incrementToken() {
        while (true) {
            if (!hasSavedState) {
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
                    first = false;
                    return true;
                }
                if (iterator.end == WordDelimiterIterator.DONE && !has(PRESERVE_ORIGINAL)) {
                    if (token.positionIncrement() == 1 && !first) {
                        accumPosInc--;
                    }
                    continue;
                }
                saveState();
                hasOutputToken = false;
                hasOutputFollowingOriginal = !has(PRESERVE_ORIGINAL);
                lastConcatCount = 0;
                if (has(PRESERVE_ORIGINAL)) {
                    token.setPositionIncrement(accumPosInc);
                    accumPosInc = 0;
                    first = false;
                    return true;
                }
            }
            if (iterator.end == WordDelimiterIterator.DONE) {
                if (!concat.isEmpty()) {
                    if (flushConcatenation(concat)) {
                        buffer();
                        continue;
                    }
                }
                if (!concatAll.isEmpty()) {
                    if (concatAll.subwordCount > lastConcatCount) {
                        concatAll.writeAndClear();
                        buffer();
                        continue;
                    }
                    concatAll.clear();
                }
                if (bufferedPos < bufferedLen) {
                    if (bufferedPos == 0) {
                        sortBuffered();
                    }
                    token.clear();
                    token.copyFrom(buffered[bufferedPos++]);
                    if (first && token.positionIncrement() == 0) {
                        token.setPositionIncrement(1);
                    }
                    first = false;
                    return true;
                }
                bufferedPos = bufferedLen = 0;
                hasSavedState = false;
                continue;
            }
            if (iterator.isSingleWord()) {
                generatePart(true);
                iterator.next();
                first = false;
                return true;
            }
            int wordType = iterator.type();
            if (!concat.isEmpty() && (concat.type & wordType) == 0) {
                if (flushConcatenation(concat)) {
                    hasOutputToken = false;
                    buffer();
                    continue;
                }
                hasOutputToken = false;
            }
            if (shouldConcatenate(wordType)) {
                if (concat.isEmpty()) {
                    concat.type = wordType;
                }
                concatenate(concat);
            }
            if (has(CATENATE_ALL)) {
                concatenate(concatAll);
            }
            if (shouldGenerateParts(wordType)) {
                generatePart(false);
                buffer();
            }
            iterator.next();
        }
    }

    @Override
    public void reset() {
        super.reset();
        hasSavedState = false;
        concat.clear();
        concatAll.clear();
        accumPosInc = bufferedPos = bufferedLen = 0;
        first = true;
    }

    private void sortBuffered() {
        int n = bufferedLen;
        if (n < 2) {
            return;
        }
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) {
            order[i] = i;
        }
        int[] so = startOff;
        int[] pi = posInc;
        Arrays.sort(order, (i, j) -> {
            int cmp = Integer.compare(so[i], so[j]);
            if (cmp == 0) {
                cmp = Integer.compare(pi[j], pi[i]);
            }
            return cmp;
        });
        Token[] b = Arrays.copyOf(buffered, n);
        int[] s = Arrays.copyOf(startOff, n);
        int[] p = Arrays.copyOf(posInc, n);
        for (int i = 0; i < n; i++) {
            int src = order[i];
            buffered[i] = b[src];
            startOff[i] = s[src];
            posInc[i] = p[src];
        }
    }

    private void buffer() {
        if (bufferedLen == buffered.length) {
            int newSize = bufferedLen + (bufferedLen >> 1) + 8;
            buffered = Arrays.copyOf(buffered, newSize);
            startOff = Arrays.copyOf(startOff, newSize);
            posInc = Arrays.copyOf(posInc, newSize);
        }
        startOff[bufferedLen] = token.startOffset();
        posInc[bufferedLen] = token.positionIncrement();
        buffered[bufferedLen] = token.copy();
        bufferedLen++;
    }

    private void saveState() {
        savedStartOffset = token.startOffset();
        savedEndOffset = token.endOffset();
        hasIllegalOffsets = savedEndOffset - savedStartOffset != token.length();
        savedType = token.type();
        if (savedBuffer.length < token.length()) {
            savedBuffer = new char[token.length() + (token.length() >> 3) + 8];
        }
        System.arraycopy(token.buffer(), 0, savedBuffer, 0, token.length());
        iterator.text = savedBuffer;
        hasSavedState = true;
    }

    private boolean flushConcatenation(Concatenation concatenation) {
        lastConcatCount = concatenation.subwordCount;
        if (concatenation.subwordCount != 1 || !shouldGenerateParts(concatenation.type)) {
            concatenation.writeAndClear();
            return true;
        }
        concatenation.clear();
        return false;
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
            concatenation.startOffset = savedStartOffset + iterator.current;
        }
        concatenation.append(savedBuffer, iterator.current, iterator.end - iterator.current);
        concatenation.endOffset = savedStartOffset + iterator.end;
    }

    private void generatePart(boolean isSingleWord) {
        token.clear();
        token.setTerm(savedBuffer, iterator.current, iterator.end - iterator.current);
        int startOffset = savedStartOffset + iterator.current;
        int endOffset = savedStartOffset + iterator.end;
        if (hasIllegalOffsets) {
            if (isSingleWord && startOffset <= savedEndOffset) {
                token.setOffset(startOffset, savedEndOffset);
            } else {
                token.setOffset(savedStartOffset, savedEndOffset);
            }
        } else {
            token.setOffset(startOffset, endOffset);
        }
        token.setPositionIncrement(position(false));
        token.setType(savedType);
    }

    private int position(boolean inject) {
        int posInc = accumPosInc;
        if (hasOutputToken) {
            accumPosInc = 0;
            return inject ? 0 : Math.max(1, posInc);
        }
        hasOutputToken = true;
        if (!hasOutputFollowingOriginal) {
            hasOutputFollowingOriginal = true;
            return 0;
        }
        accumPosInc = 0;
        return Math.max(1, posInc);
    }

    private boolean has(int flag) {
        return (flags & flag) != 0;
    }

    private final class Concatenation {
        final StringBuilder buffer = new StringBuilder();
        int startOffset;
        int endOffset;
        int type;
        int subwordCount;

        void append(char[] text, int offset, int length) {
            buffer.append(text, offset, length);
            subwordCount++;
        }

        void write() {
            token.clear();
            token.setTerm(buffer);
            if (hasIllegalOffsets) {
                token.setOffset(savedStartOffset, savedEndOffset);
            } else {
                token.setOffset(startOffset, endOffset);
            }
            token.setPositionIncrement(position(true));
            token.setType(savedType);
            accumPosInc = 0;
        }

        boolean isEmpty() {
            return buffer.length() == 0;
        }

        void clear() {
            buffer.setLength(0);
            startOffset = endOffset = type = subwordCount = 0;
        }

        void writeAndClear() {
            write();
            clear();
        }
    }

    @Override
    public String toString() {
        return "WordDelimiterFilter(flags=" + WordDelimiterGraphFilter.flagsToString(flags) + ')';
    }
}
