package com.naqqa.elasticsearch.analysis;

import java.util.Arrays;

public final class Token implements CharSequence {

    public static final String DEFAULT_TYPE = "word";

    private char[] term = new char[16];
    private int termLength;
    private int positionIncrement = 1;
    private int positionLength = 1;
    private int startOffset;
    private int endOffset;
    private String type = DEFAULT_TYPE;
    private boolean keyword;
    private byte[] payload;
    private int flags;
    private int termFrequency = 1;

    public Token() {
    }

    public Token(String term, int startOffset, int endOffset) {
        setTerm(term);
        this.startOffset = startOffset;
        this.endOffset = endOffset;
    }

    public char[] buffer() {
        return term;
    }

    public char[] resizeBuffer(int newSize) {
        if (term.length < newSize) {
            int size = Math.max(newSize, term.length + (term.length >> 1) + 8);
            term = Arrays.copyOf(term, size);
        }
        return term;
    }

    @Override
    public int length() {
        return termLength;
    }

    public Token setLength(int length) {
        if (length > term.length) {
            resizeBuffer(length);
        }
        termLength = length;
        return this;
    }

    public Token setEmpty() {
        termLength = 0;
        return this;
    }

    public Token setTerm(CharSequence s) {
        int len = s.length();
        resizeBuffer(len);
        if (s instanceof String str) {
            str.getChars(0, len, term, 0);
        } else if (s instanceof Token t) {
            System.arraycopy(t.term, 0, term, 0, len);
        } else {
            for (int i = 0; i < len; i++) {
                term[i] = s.charAt(i);
            }
        }
        termLength = len;
        return this;
    }

    public Token setTerm(CharSequence s, int start, int end) {
        int len = end - start;
        resizeBuffer(len);
        if (s instanceof String str) {
            str.getChars(start, end, term, 0);
        } else {
            for (int i = 0; i < len; i++) {
                term[i] = s.charAt(start + i);
            }
        }
        termLength = len;
        return this;
    }

    public Token setTerm(char[] buf, int off, int len) {
        resizeBuffer(len);
        System.arraycopy(buf, off, term, 0, len);
        termLength = len;
        return this;
    }

    public Token append(char c) {
        resizeBuffer(termLength + 1);
        term[termLength++] = c;
        return this;
    }

    public Token append(CharSequence s) {
        int len = s.length();
        resizeBuffer(termLength + len);
        for (int i = 0; i < len; i++) {
            term[termLength + i] = s.charAt(i);
        }
        termLength += len;
        return this;
    }

    public Token appendCodePoint(int cp) {
        if (Character.isBmpCodePoint(cp)) {
            return append((char) cp);
        }
        resizeBuffer(termLength + 2);
        term[termLength++] = Character.highSurrogate(cp);
        term[termLength++] = Character.lowSurrogate(cp);
        return this;
    }

    @Override
    public char charAt(int index) {
        if (index >= termLength) {
            throw new IndexOutOfBoundsException(index);
        }
        return term[index];
    }

    @Override
    public CharSequence subSequence(int start, int end) {
        return new String(term, start, end - start);
    }

    public String term() {
        return new String(term, 0, termLength);
    }

    public boolean termEquals(CharSequence other) {
        if (other.length() != termLength) {
            return false;
        }
        for (int i = 0; i < termLength; i++) {
            if (term[i] != other.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public String toString() {
        return term();
    }

    public int positionIncrement() {
        return positionIncrement;
    }

    public Token setPositionIncrement(int positionIncrement) {
        if (positionIncrement < 0) {
            throw new IllegalArgumentException("position increment must be >= 0, got " + positionIncrement);
        }
        this.positionIncrement = positionIncrement;
        return this;
    }

    public int positionLength() {
        return positionLength;
    }

    public Token setPositionLength(int positionLength) {
        if (positionLength < 1) {
            throw new IllegalArgumentException("position length must be >= 1, got " + positionLength);
        }
        this.positionLength = positionLength;
        return this;
    }

    public int startOffset() {
        return startOffset;
    }

    public int endOffset() {
        return endOffset;
    }

    public Token setOffset(int startOffset, int endOffset) {
        if (startOffset < 0 || endOffset < startOffset) {
            throw new IllegalArgumentException("startOffset must be non-negative, and endOffset must be >= startOffset; got startOffset=" + startOffset + ",endOffset=" + endOffset);
        }
        this.startOffset = startOffset;
        this.endOffset = endOffset;
        return this;
    }

    public String type() {
        return type;
    }

    public Token setType(String type) {
        this.type = type;
        return this;
    }

    public boolean isKeyword() {
        return keyword;
    }

    public Token setKeyword(boolean keyword) {
        this.keyword = keyword;
        return this;
    }

    public byte[] payload() {
        return payload;
    }

    public Token setPayload(byte[] payload) {
        this.payload = payload;
        return this;
    }

    public int flags() {
        return flags;
    }

    public Token setFlags(int flags) {
        this.flags = flags;
        return this;
    }

    public int termFrequency() {
        return termFrequency;
    }

    public Token setTermFrequency(int termFrequency) {
        if (termFrequency < 1) {
            throw new IllegalArgumentException("term frequency must be 1 or greater; got " + termFrequency);
        }
        this.termFrequency = termFrequency;
        return this;
    }

    public static final int ATTRIBUTE_KEYWORD = 1;
    public static final int ATTRIBUTE_PAYLOAD = 2;
    public static final int ATTRIBUTE_FLAGS = 4;

    private int declaredAttributes;

    public void declareAttribute(int attribute) {
        declaredAttributes |= attribute;
    }

    public boolean hasAttribute(int attribute) {
        return (declaredAttributes & attribute) != 0;
    }

    public void clear() {
        termLength = 0;
        positionIncrement = 1;
        positionLength = 1;
        startOffset = 0;
        endOffset = 0;
        type = DEFAULT_TYPE;
        keyword = false;
        payload = null;
        flags = 0;
        termFrequency = 1;
    }

    public void copyFrom(Token other) {
        resizeBuffer(other.termLength);
        System.arraycopy(other.term, 0, term, 0, other.termLength);
        termLength = other.termLength;
        positionIncrement = other.positionIncrement;
        positionLength = other.positionLength;
        startOffset = other.startOffset;
        endOffset = other.endOffset;
        type = other.type;
        keyword = other.keyword;
        payload = other.payload;
        flags = other.flags;
        termFrequency = other.termFrequency;
    }

    public Token copy() {
        Token t = new Token();
        t.copyFrom(this);
        return t;
    }
}
