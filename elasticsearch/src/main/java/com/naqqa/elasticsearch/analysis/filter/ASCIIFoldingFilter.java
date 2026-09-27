package com.naqqa.elasticsearch.analysis.filter;

import com.naqqa.elasticsearch.analysis.Token;
import com.naqqa.elasticsearch.analysis.TokenFilter;
import com.naqqa.elasticsearch.analysis.TokenStream;
import com.naqqa.elasticsearch.analysis.folding.AsciiFolder;

public final class ASCIIFoldingFilter extends TokenFilter {

    private final boolean preserveOriginal;
    private char[] output = new char[512];
    private Token pendingOriginal;

    public ASCIIFoldingFilter(TokenStream input, boolean preserveOriginal) {
        super(input);
        this.preserveOriginal = preserveOriginal;
    }

    @Override
    public boolean incrementToken() {
        if (pendingOriginal != null) {
            token.clear();
            token.copyFrom(pendingOriginal);
            pendingOriginal = null;
            return true;
        }
        if (!input.incrementToken()) {
            return false;
        }
        char[] buf = token.buffer();
        int len = token.length();
        boolean needsFolding = false;
        for (int i = 0; i < len; i++) {
            if (buf[i] >= 0x80) {
                needsFolding = true;
                break;
            }
        }
        if (!needsFolding) {
            return true;
        }
        if (preserveOriginal) {
            pendingOriginal = token.copy();
            pendingOriginal.setPositionIncrement(0);
        }
        if (output.length < len * 4) {
            output = new char[len * 4];
        }
        int newLen = AsciiFolder.foldToASCII(buf, 0, output, 0, len);
        token.setTerm(output, 0, newLen);
        return true;
    }

    @Override
    public void reset() {
        super.reset();
        pendingOriginal = null;
    }
}
