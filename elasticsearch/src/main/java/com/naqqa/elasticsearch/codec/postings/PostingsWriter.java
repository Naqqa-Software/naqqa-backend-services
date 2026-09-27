package com.naqqa.elasticsearch.codec.postings;

import com.naqqa.elasticsearch.codec.ForUtil;
import com.naqqa.elasticsearch.store.BytesDataOutput;
import com.naqqa.elasticsearch.store.DataOutput;
import com.naqqa.elasticsearch.store.IndexOutput;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public final class PostingsWriter {

    public static final int SKIP_INTERVAL = 16;

    private final IndexOutput out;
    private final int flags;

    private int[] docIds = new int[128];
    private int[] freqs = new int[128];
    private int docCount;
    private long totalTermFreq;

    private final BytesDataOutput positionsBuffer = new BytesDataOutput();
    private byte curNorm;
    private byte[] norms = new byte[128];

    private int curDoc = -1;
    private int curFreq;
    private int curPositionCount;
    private int lastPosition;
    private int lastOffsetEnd;

    public PostingsWriter(IndexOutput out, int flags) {
        this.out = out;
        this.flags = flags;
    }

    public void startDoc(int docId, int freq, byte norm) throws IOException {
        finishDoc();
        curDoc = docId;
        curFreq = PostingsFlags.hasFreqs(flags) ? freq : 1;
        curNorm = norm;
        curPositionCount = 0;
        lastPosition = 0;
        lastOffsetEnd = 0;
    }

    public void addPosition(int position, int startOffset, int endOffset, byte[] payload) throws IOException {
        if (!PostingsFlags.hasPositions(flags)) {
            return;
        }
        positionsBuffer.writeVInt(position - lastPosition);
        lastPosition = position;
        if (PostingsFlags.hasOffsets(flags)) {
            positionsBuffer.writeVInt(startOffset - lastOffsetEnd);
            positionsBuffer.writeVInt(endOffset - startOffset);
            lastOffsetEnd = endOffset;
        }
        if (PostingsFlags.hasPayloads(flags)) {
            if (payload == null) {
                positionsBuffer.writeVInt(0);
            } else {
                positionsBuffer.writeVInt(payload.length);
                positionsBuffer.writeBytes(payload, 0, payload.length);
            }
        }
        curPositionCount++;
    }

    private void finishDoc() {
        if (curDoc < 0) {
            return;
        }
        ensureCapacity(docCount + 1);
        docIds[docCount] = curDoc;
        freqs[docCount] = curFreq;
        norms[docCount] = curNorm;
        docCount++;
        totalTermFreq += curFreq;
        curDoc = -1;
    }

    private void ensureCapacity(int needed) {
        if (needed > docIds.length) {
            int newLen = Math.max(needed, docIds.length * 2);
            int[] nd = new int[newLen];
            int[] nf = new int[newLen];
            byte[] nn = new byte[newLen];
            System.arraycopy(docIds, 0, nd, 0, docCount);
            System.arraycopy(freqs, 0, nf, 0, docCount);
            System.arraycopy(norms, 0, nn, 0, docCount);
            docIds = nd;
            freqs = nf;
            norms = nn;
        }
    }

    public TermStats finishTerm() throws IOException {
        finishDoc();
        long start = out.getFilePointer();
        out.writeVInt(docCount);
        out.writeVLong(totalTermFreq);
        int numFullBlocks = docCount / ForUtil.BLOCK_SIZE;
        int tailSize = docCount % ForUtil.BLOCK_SIZE;
        out.writeVInt(numFullBlocks);
        out.writeVInt(tailSize);

        List<SkipEntry> level0 = new ArrayList<>();
        long cumFreq = 0;
        int prevDocId = -1;

        BytesDataOutput blocksOut = new BytesDataOutput();
        int[] deltaBuf = new int[ForUtil.BLOCK_SIZE];
        int[] freqBuf = new int[ForUtil.BLOCK_SIZE];
        for (int b = 0; b < numFullBlocks; b++) {
            int base = b * ForUtil.BLOCK_SIZE;
            long blockStart = blocksOut.size();
            int maxFreq = 0;
            byte minNorm = Byte.MAX_VALUE;
            for (int i = 0; i < ForUtil.BLOCK_SIZE; i++) {
                int doc = docIds[base + i];
                deltaBuf[i] = doc - prevDocId - 1;
                prevDocId = doc;
                freqBuf[i] = freqs[base + i];
                maxFreq = Math.max(maxFreq, freqBuf[i]);
                if (norms[base + i] < minNorm) {
                    minNorm = norms[base + i];
                }
            }
            level0.add(new SkipEntry(docIds[base + ForUtil.BLOCK_SIZE - 1], blockStart, maxFreq, cumFreq, minNorm));
            ForUtil.encodeBlock(deltaBuf, blocksOut);
            if (PostingsFlags.hasFreqs(flags)) {
                ForUtil.encodeBlock(freqBuf, blocksOut);
            }
            for (int i = 0; i < ForUtil.BLOCK_SIZE; i++) {
                cumFreq += freqBuf[i];
            }
        }
        for (int i = numFullBlocks * ForUtil.BLOCK_SIZE; i < docCount; i++) {
            int doc = docIds[i];
            blocksOut.writeVInt(doc - prevDocId - 1);
            prevDocId = doc;
            if (PostingsFlags.hasFreqs(flags)) {
                blocksOut.writeVInt(freqs[i]);
            }
        }

        List<SkipEntry> level1 = numFullBlocks > 0 ? buildLevel1(level0) : List.of();
        int skipDataLength = numFullBlocks > 0 ? skipDataByteLength(level0.size(), level1.size()) : 0;
        long headerFixedPart = out.getFilePointer();
        int posStartFieldSize = PostingsFlags.hasPositions(flags) ? 8 : 0;
        long blocksSectionStart = headerFixedPart + posStartFieldSize
            + (numFullBlocks > 0 ? DataOutput.vIntSize(skipDataLength) + skipDataLength : 0);
        long positionsSectionStart = blocksSectionStart + blocksOut.size();

        if (PostingsFlags.hasPositions(flags)) {
            out.writeLong(positionsSectionStart);
        }
        if (numFullBlocks > 0) {
            for (SkipEntry e : level0) {
                e.filePointer += blocksSectionStart;
            }
            for (SkipEntry e : level1) {
                e.filePointer += blocksSectionStart;
            }
            byte[] skipData = serializeSkipData(level0, level1);
            if (skipData.length != skipDataLength) {
                throw new IllegalStateException("skip data length mismatch");
            }
            out.writeVInt(skipData.length);
            out.writeBytes(skipData, 0, skipData.length);
        }
        out.writeBytes(blocksOut.getBytes(), 0, blocksOut.size());

        if (PostingsFlags.hasPositions(flags)) {
            out.writeVLong(positionsBuffer.size());
            out.writeBytes(positionsBuffer.getBytes(), 0, positionsBuffer.size());
        }

        int result = docCount;
        long ttf = totalTermFreq;
        reset();
        return new TermStats(result, ttf, start);
    }

    private static List<SkipEntry> buildLevel1(List<SkipEntry> level0) {
        List<SkipEntry> level1 = new ArrayList<>();
        for (int i = 0; i < level0.size(); i += SKIP_INTERVAL) {
            int groupEnd = Math.min(i + SKIP_INTERVAL, level0.size()) - 1;
            SkipEntry first = level0.get(i);
            SkipEntry last = level0.get(groupEnd);
            byte minNorm = Byte.MAX_VALUE;
            int maxFreq = 0;
            for (int k = i; k <= groupEnd; k++) {
                maxFreq = Math.max(maxFreq, level0.get(k).maxFreq);
                if (level0.get(k).minNorm < minNorm) {
                    minNorm = level0.get(k).minNorm;
                }
            }
            level1.add(new SkipEntry(last.lastDocId, first.filePointer, maxFreq, first.cumFreqBefore, minNorm));
        }
        return level1;
    }

    private static int skipDataByteLength(int level0Count, int level1Count) {
        return DataOutput.vIntSize(level0Count) + level0Count * SKIP_ENTRY_SIZE
            + DataOutput.vIntSize(level1Count) + level1Count * SKIP_ENTRY_SIZE;
    }

    private static byte[] serializeSkipData(List<SkipEntry> level0, List<SkipEntry> level1) throws IOException {
        BytesDataOutput skip = new BytesDataOutput();
        skip.writeVInt(level0.size());
        for (SkipEntry e : level0) {
            writeSkipEntry(skip, e);
        }
        skip.writeVInt(level1.size());
        for (SkipEntry e : level1) {
            writeSkipEntry(skip, e);
        }
        return skip.toArrayCopy();
    }

    private static void writeSkipEntry(BytesDataOutput out, SkipEntry e) throws IOException {
        out.writeInt(e.lastDocId);
        out.writeLong(e.filePointer);
        out.writeInt(e.maxFreq);
        out.writeLong(e.cumFreqBefore);
        out.writeByte(e.minNorm);
    }

    static final int SKIP_ENTRY_SIZE = 4 + 8 + 4 + 8 + 1;

    private void reset() {
        docCount = 0;
        totalTermFreq = 0;
        positionsBuffer.reset();
        curDoc = -1;
    }

    private static final class SkipEntry {
        final int lastDocId;
        long filePointer;
        final int maxFreq;
        final long cumFreqBefore;
        final byte minNorm;

        SkipEntry(int lastDocId, long filePointer, int maxFreq, long cumFreqBefore, byte minNorm) {
            this.lastDocId = lastDocId;
            this.filePointer = filePointer;
            this.maxFreq = maxFreq;
            this.cumFreqBefore = cumFreqBefore;
            this.minNorm = minNorm;
        }
    }
}
