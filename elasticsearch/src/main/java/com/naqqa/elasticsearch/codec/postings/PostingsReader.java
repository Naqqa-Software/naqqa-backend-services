package com.naqqa.elasticsearch.codec.postings;

import com.naqqa.elasticsearch.codec.DocIdSetIterator;
import com.naqqa.elasticsearch.codec.ForUtil;
import com.naqqa.elasticsearch.store.ByteArrayDataInput;
import com.naqqa.elasticsearch.store.IndexInput;

import java.io.IOException;

public final class PostingsReader {

    private final IndexInput baseIn;
    private final long filePointer;
    private final int flags;
    private final int docFreq;
    private final long totalTermFreq;

    public PostingsReader(IndexInput in, long filePointer, int flags) throws IOException {
        this.baseIn = in;
        this.filePointer = filePointer;
        this.flags = flags;
        IndexInput tmp = in.clone();
        tmp.seek(filePointer);
        this.docFreq = tmp.readVInt();
        this.totalTermFreq = tmp.readVLong();
    }

    public int docFreq() {
        return docFreq;
    }

    public long totalTermFreq() {
        return totalTermFreq;
    }

    public PostingsEnum postings() throws IOException {
        return new BlockPostingsEnum(baseIn.clone(), filePointer, flags);
    }

    private static final class SkipEntry {
        int lastDocId;
        long filePointer;
        int maxFreq;
        long cumFreqBefore;
        byte minNorm;
    }

    static final class BlockPostingsEnum extends PostingsEnum {

        private final IndexInput in;
        private final int flags;
        private final int docFreq;
        private final int numFullBlocks;
        private final int tailSize;
        private final long blocksSectionStart;
        private final long positionsSectionStart;
        private final SkipEntry[] level0;
        private final SkipEntry[] level1;

        private int doc = -1;
        private int docOrdinal = -1;
        private int curFreq;
        private long cumFreqSoFar;
        private long cumFreqBeforeCurrentDoc;
        private long prevDocIdForDelta = -1;

        private final int[] blockDocs = new int[ForUtil.BLOCK_SIZE];
        private final int[] blockFreqs = new int[ForUtil.BLOCK_SIZE];
        private int blockLoadedIndex = -1;

        private IndexInput positionsIn;
        private long positionsStreamCumFreq;
        private int positionsReadForCurrentDoc;
        private int lastPosition;
        private int lastOffsetEnd;
        private int curStartOffset;
        private int curEndOffset;
        private byte[] curPayload;

        BlockPostingsEnum(IndexInput in, long filePointer, int flags) throws IOException {
            this.in = in;
            this.flags = flags;
            in.seek(filePointer);
            this.docFreq = in.readVInt();
            in.readVLong();
            this.numFullBlocks = in.readVInt();
            this.tailSize = in.readVInt();
            long posStart = -1;
            if (PostingsFlags.hasPositions(flags)) {
                posStart = in.readLong();
            }
            this.positionsSectionStart = posStart;
            SkipEntry[] l0 = new SkipEntry[0];
            SkipEntry[] l1 = new SkipEntry[0];
            if (numFullBlocks > 0) {
                int skipLen = in.readVInt();
                byte[] skipBytes = new byte[skipLen];
                in.readBytes(skipBytes, 0, skipLen);
                ByteArrayDataInput skipIn = new ByteArrayDataInput(skipBytes);
                int n0 = skipIn.readVInt();
                l0 = new SkipEntry[n0];
                for (int i = 0; i < n0; i++) {
                    l0[i] = readSkipEntry(skipIn);
                }
                int n1 = skipIn.readVInt();
                l1 = new SkipEntry[n1];
                for (int i = 0; i < n1; i++) {
                    l1[i] = readSkipEntry(skipIn);
                }
            }
            this.level0 = l0;
            this.level1 = l1;
            this.blocksSectionStart = in.getFilePointer();
        }

        private static SkipEntry readSkipEntry(ByteArrayDataInput in) throws IOException {
            SkipEntry e = new SkipEntry();
            e.lastDocId = in.readInt();
            e.filePointer = in.readLong();
            e.maxFreq = in.readInt();
            e.cumFreqBefore = in.readLong();
            e.minNorm = in.readByte();
            return e;
        }

        @Override
        public int docID() {
            return doc;
        }

        @Override
        public long cost() {
            return docFreq;
        }

        @Override
        public int nextDoc() throws IOException {
            if (doc == NO_MORE_DOCS) {
                return NO_MORE_DOCS;
            }
            docOrdinal++;
            if (docOrdinal >= docFreq) {
                doc = NO_MORE_DOCS;
                return NO_MORE_DOCS;
            }
            int blockIdx = docOrdinal / ForUtil.BLOCK_SIZE;
            int posWithin = docOrdinal % ForUtil.BLOCK_SIZE;
            if (blockIdx < numFullBlocks) {
                if (blockLoadedIndex != blockIdx) {
                    loadBlock(blockIdx);
                }
                doc = blockDocs[posWithin];
                curFreq = blockFreqs[posWithin];
            } else {
                int delta = in.readVInt();
                prevDocIdForDelta = prevDocIdForDelta + delta + 1;
                doc = (int) prevDocIdForDelta;
                curFreq = PostingsFlags.hasFreqs(flags) ? in.readVInt() : 1;
            }
            cumFreqBeforeCurrentDoc = cumFreqSoFar;
            cumFreqSoFar += curFreq;
            positionsReadForCurrentDoc = 0;
            lastPosition = 0;
            lastOffsetEnd = 0;
            return doc;
        }

        private void loadBlock(int blockIdx) throws IOException {
            if (blockLoadedIndex != blockIdx - 1) {
                seekToBlock(blockIdx);
            }
            int[] deltaBuf = new int[ForUtil.BLOCK_SIZE];
            ForUtil.decodeBlock(in, deltaBuf);
            long prev = prevDocIdForDelta;
            for (int i = 0; i < ForUtil.BLOCK_SIZE; i++) {
                prev = prev + deltaBuf[i] + 1;
                blockDocs[i] = (int) prev;
            }
            prevDocIdForDelta = prev;
            if (PostingsFlags.hasFreqs(flags)) {
                ForUtil.decodeBlock(in, blockFreqs);
            } else {
                java.util.Arrays.fill(blockFreqs, 1);
            }
            blockLoadedIndex = blockIdx;
        }

        private void seekToBlock(int blockIdx) throws IOException {
            in.seek(level0[blockIdx].filePointer);
            prevDocIdForDelta = blockIdx == 0 ? -1 : level0[blockIdx - 1].lastDocId;
            cumFreqSoFar = level0[blockIdx].cumFreqBefore;
        }

        @Override
        public int advance(int target) throws IOException {
            if (doc == NO_MORE_DOCS) {
                return NO_MORE_DOCS;
            }
            trySkipJump(target);
            int d;
            do {
                d = nextDoc();
            } while (d != NO_MORE_DOCS && d < target);
            return d;
        }

        private void trySkipJump(int target) throws IOException {
            if (level0.length == 0) {
                return;
            }
            int startBlock = docOrdinal < 0 ? 0 : docOrdinal / ForUtil.BLOCK_SIZE;
            int group = -1;
            for (int g = startBlock / PostingsWriter.SKIP_INTERVAL; g < level1.length; g++) {
                if (level1[g].lastDocId >= target) {
                    group = g;
                    break;
                }
            }
            if (group < 0) {
                return;
            }
            int lo = Math.max(startBlock, group * PostingsWriter.SKIP_INTERVAL);
            int hi = Math.min(level0.length, (group + 1) * PostingsWriter.SKIP_INTERVAL) - 1;
            int target0 = -1;
            for (int b = lo; b <= hi; b++) {
                if (level0[b].lastDocId >= target) {
                    target0 = b;
                    break;
                }
            }
            if (target0 < 0 || target0 <= startBlock) {
                return;
            }
            seekToBlock(target0);
            blockLoadedIndex = target0 - 1;
            docOrdinal = target0 * ForUtil.BLOCK_SIZE - 1;
        }

        @Override
        public int freq() {
            return curFreq;
        }

        @Override
        public int nextPosition() throws IOException {
            if (!PostingsFlags.hasPositions(flags)) {
                return -1;
            }
            ensurePositionsStream();
            catchUpPositionsStream();
            lastPosition += positionsIn.readVInt();
            if (PostingsFlags.hasOffsets(flags)) {
                curStartOffset = lastOffsetEnd + positionsIn.readVInt();
                curEndOffset = curStartOffset + positionsIn.readVInt();
                lastOffsetEnd = curEndOffset;
            }
            if (PostingsFlags.hasPayloads(flags)) {
                int len = positionsIn.readVInt();
                if (len > 0) {
                    curPayload = new byte[len];
                    positionsIn.readBytes(curPayload, 0, len);
                } else {
                    curPayload = null;
                }
            }
            positionsReadForCurrentDoc++;
            positionsStreamCumFreq++;
            return lastPosition;
        }

        private void ensurePositionsStream() throws IOException {
            if (positionsIn == null) {
                positionsIn = in.clone();
                positionsIn.seek(positionsSectionStart);
                positionsIn.readVLong();
            }
        }

        private void catchUpPositionsStream() throws IOException {
            while (positionsStreamCumFreq < cumFreqBeforeCurrentDoc) {
                skipOnePositionGroup();
                positionsStreamCumFreq++;
            }
        }

        private void skipOnePositionGroup() throws IOException {
            positionsIn.readVInt();
            if (PostingsFlags.hasOffsets(flags)) {
                positionsIn.readVInt();
                positionsIn.readVInt();
            }
            if (PostingsFlags.hasPayloads(flags)) {
                int len = positionsIn.readVInt();
                if (len > 0) {
                    positionsIn.skipBytes(len);
                }
            }
        }

        @Override
        public int startOffset() {
            return curStartOffset;
        }

        @Override
        public int endOffset() {
            return curEndOffset;
        }

        @Override
        public byte[] getPayload() {
            return curPayload;
        }
    }
}
