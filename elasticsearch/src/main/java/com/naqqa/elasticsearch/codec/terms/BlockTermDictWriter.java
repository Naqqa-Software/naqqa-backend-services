package com.naqqa.elasticsearch.codec.terms;

import com.naqqa.elasticsearch.codec.fst.FST;
import com.naqqa.elasticsearch.codec.fst.FSTBuilder;
import com.naqqa.elasticsearch.codec.postings.TermStats;
import com.naqqa.elasticsearch.store.CodecUtil;
import com.naqqa.elasticsearch.store.IndexOutput;

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public final class BlockTermDictWriter implements Closeable {

    public static final String CODEC_NAME = "NaqqaTermDict";
    public static final int VERSION_START = 1;
    public static final int VERSION_CURRENT = 1;
    public static final int BLOCK_SIZE = 32;

    private final IndexOutput out;
    private final List<byte[]> pendingTerms = new ArrayList<>();
    private final List<TermStats> pendingStats = new ArrayList<>();
    private final List<byte[]> blockFirstTerms = new ArrayList<>();
    private final List<Long> blockFilePointers = new ArrayList<>();
    private byte[] lastTerm;
    private long numTerms;
    private long sumDocFreq;
    private long sumTotalTermFreq;
    private boolean finished;

    public BlockTermDictWriter(IndexOutput out) throws IOException {
        this.out = out;
        CodecUtil.writeHeader(out, CODEC_NAME, VERSION_CURRENT);
    }

    public void addTerm(byte[] term, TermStats stats) throws IOException {
        if (lastTerm != null && compare(lastTerm, term) >= 0) {
            throw new IllegalArgumentException("terms must be added in strictly ascending order");
        }
        pendingTerms.add(term.clone());
        pendingStats.add(stats);
        numTerms++;
        sumDocFreq += stats.docFreq();
        sumTotalTermFreq += stats.totalTermFreq();
        lastTerm = term;
        if (pendingTerms.size() == BLOCK_SIZE) {
            flushBlock();
        }
    }

    private static int compare(byte[] a, byte[] b) {
        int n = Math.min(a.length, b.length);
        for (int i = 0; i < n; i++) {
            int ai = a[i] & 0xFF;
            int bi = b[i] & 0xFF;
            if (ai != bi) {
                return ai - bi;
            }
        }
        return a.length - b.length;
    }

    private static int commonPrefixLength(byte[] a, byte[] b) {
        int n = Math.min(a.length, b.length);
        int i = 0;
        while (i < n && a[i] == b[i]) {
            i++;
        }
        return i;
    }

    private void flushBlock() throws IOException {
        if (pendingTerms.isEmpty()) {
            return;
        }
        long blockStart = out.getFilePointer();
        blockFirstTerms.add(pendingTerms.get(0));
        blockFilePointers.add(blockStart);
        out.writeVInt(pendingTerms.size());
        byte[] prev = new byte[0];
        for (int i = 0; i < pendingTerms.size(); i++) {
            byte[] term = pendingTerms.get(i);
            int prefixLen = commonPrefixLength(prev, term);
            int suffixLen = term.length - prefixLen;
            out.writeVInt(prefixLen);
            out.writeVInt(suffixLen);
            out.writeBytes(term, prefixLen, suffixLen);
            TermStats st = pendingStats.get(i);
            out.writeVInt(st.docFreq());
            out.writeVLong(st.totalTermFreq());
            out.writeVLong(st.postingsFilePointer());
            prev = term;
        }
        pendingTerms.clear();
        pendingStats.clear();
    }

    public void finish() throws IOException {
        if (finished) {
            return;
        }
        finished = true;
        flushBlock();
        long fstBlobOffset = out.getFilePointer();
        FSTBuilder builder = new FSTBuilder();
        for (int i = 0; i < blockFirstTerms.size(); i++) {
            builder.add(blockFirstTerms.get(i), blockFilePointers.get(i));
        }
        FST fst = builder.build();
        fst.save(out);
        out.writeLong(sumDocFreq);
        out.writeLong(sumTotalTermFreq);
        out.writeLong(numTerms);
        out.writeLong(fstBlobOffset);
        out.writeInt(blockFirstTerms.size());
        CodecUtil.writeFooter(out);
    }

    @Override
    public void close() throws IOException {
        finish();
        out.close();
    }
}
