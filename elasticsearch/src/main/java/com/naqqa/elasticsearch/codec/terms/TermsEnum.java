package com.naqqa.elasticsearch.codec.terms;

import com.naqqa.elasticsearch.codec.postings.PostingsEnum;

import java.io.IOException;

public interface TermsEnum {

    byte[] next() throws IOException;

    boolean seekExact(byte[] text) throws IOException;

    SeekStatus seekCeil(byte[] text) throws IOException;

    byte[] term();

    int docFreq() throws IOException;

    long totalTermFreq() throws IOException;

    PostingsEnum postings(int flags) throws IOException;
}
