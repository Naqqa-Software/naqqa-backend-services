package com.naqqa.elasticsearch.codec.terms;

import com.naqqa.elasticsearch.codec.ByteAutomaton;
import com.naqqa.elasticsearch.codec.fst.FST;
import com.naqqa.elasticsearch.codec.fst.FSTEnum;
import com.naqqa.elasticsearch.codec.postings.PostingsEnum;
import com.naqqa.elasticsearch.codec.postings.PostingsReader;
import com.naqqa.elasticsearch.codec.postings.TermStats;
import com.naqqa.elasticsearch.store.CodecUtil;
import com.naqqa.elasticsearch.store.IndexInput;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public final class BlockTermDictReader {

    private static final int TRAILER_SIZE = 8 + 8 + 8 + 8 + 4;

    private final IndexInput dictIn;
    private final IndexInput postingsIn;
    private final long numTerms;
    private final long sumDocFreq;
    private final long sumTotalTermFreq;
    private final long blocksSectionStart;
    private final int numBlocks;
    private final FST fst;
    private final byte[][] blockFirstTerms;
    private final long[] blockPointers;

    public BlockTermDictReader(IndexInput dictIn, IndexInput postingsIn) throws IOException {
        CodecUtil.checksumEntireFile(dictIn);
        this.dictIn = dictIn;
        this.postingsIn = postingsIn;
        CodecUtil.checkHeader(dictIn, BlockTermDictWriter.CODEC_NAME, BlockTermDictWriter.VERSION_START, BlockTermDictWriter.VERSION_CURRENT);
        this.blocksSectionStart = dictIn.getFilePointer();
        long trailerPos = dictIn.length() - CodecUtil.footerLength() - TRAILER_SIZE;
        dictIn.seek(trailerPos);
        this.sumDocFreq = dictIn.readLong();
        this.sumTotalTermFreq = dictIn.readLong();
        this.numTerms = dictIn.readLong();
        long fstBlobOffset = dictIn.readLong();
        this.numBlocks = dictIn.readInt();
        dictIn.seek(fstBlobOffset);
        this.fst = FST.load(dictIn);
        List<byte[]> terms = new ArrayList<>();
        List<Long> pointers = new ArrayList<>();
        FSTEnum it = fst.iterator();
        while (it.next()) {
            terms.add(it.term().clone());
            pointers.add(it.output());
        }
        this.blockFirstTerms = terms.toArray(new byte[0][]);
        this.blockPointers = new long[pointers.size()];
        for (int i = 0; i < pointers.size(); i++) {
            this.blockPointers[i] = pointers.get(i);
        }
    }

    public long numTerms() {
        return numTerms;
    }

    public long sumDocFreq() {
        return sumDocFreq;
    }

    public long sumTotalTermFreq() {
        return sumTotalTermFreq;
    }

    public int prefixIndexLookup(byte[] term) {
        return floorBlockIndex(term);
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

    private int floorBlockIndex(byte[] target) {
        int lo = 0;
        int hi = blockFirstTerms.length - 1;
        int result = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (compare(blockFirstTerms[mid], target) <= 0) {
                result = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return result;
    }

    public TermsEnum iterator() throws IOException {
        return new Enum();
    }

    public TermsEnum intersect(ByteAutomaton automaton) throws IOException {
        Enum full = new Enum();
        return new TermsEnum() {
            private byte[] current;

            @Override
            public byte[] next() throws IOException {
                byte[] t;
                while ((t = full.next()) != null) {
                    if (accepts(automaton, t)) {
                        current = t;
                        return t;
                    }
                }
                current = null;
                return null;
            }

            @Override
            public boolean seekExact(byte[] text) {
                throw new UnsupportedOperationException("intersect enum does not support seek");
            }

            @Override
            public SeekStatus seekCeil(byte[] text) {
                throw new UnsupportedOperationException("intersect enum does not support seek");
            }

            @Override
            public byte[] term() {
                return current;
            }

            @Override
            public int docFreq() throws IOException {
                return full.docFreq();
            }

            @Override
            public long totalTermFreq() throws IOException {
                return full.totalTermFreq();
            }

            @Override
            public PostingsEnum postings(int flags) throws IOException {
                return full.postings(flags);
            }
        };
    }

    private static boolean accepts(ByteAutomaton automaton, byte[] term) {
        int state = automaton.initial();
        for (byte b : term) {
            state = automaton.step(state, b & 0xFF);
            if (state < 0) {
                return false;
            }
        }
        return automaton.isAccept(state);
    }

    private final class Enum implements TermsEnum {
        private final IndexInput cursor;
        private int blockOrdinal = -1;
        private byte[][] curBlockTerms;
        private TermStats[] curBlockStats;
        private int curBlockCount;
        private int posInBlock;
        private byte[] current;
        private TermStats currentStats;

        Enum() throws IOException {
            cursor = dictIn.clone();
            cursor.seek(blocksSectionStart);
        }

        private void loadBlockAt(int idx, long filePointer) throws IOException {
            cursor.seek(filePointer);
            loadBlockFromCursor(idx);
        }

        private void loadBlockFromCursor(int idx) throws IOException {
            int count = cursor.readVInt();
            byte[][] terms = new byte[count][];
            TermStats[] stats = new TermStats[count];
            byte[] prev = new byte[0];
            for (int i = 0; i < count; i++) {
                int prefixLen = cursor.readVInt();
                int suffixLen = cursor.readVInt();
                byte[] term = new byte[prefixLen + suffixLen];
                System.arraycopy(prev, 0, term, 0, prefixLen);
                cursor.readBytes(term, prefixLen, suffixLen);
                int docFreq = cursor.readVInt();
                long ttf = cursor.readVLong();
                long fp = cursor.readVLong();
                terms[i] = term;
                stats[i] = new TermStats(docFreq, ttf, fp);
                prev = term;
            }
            curBlockTerms = terms;
            curBlockStats = stats;
            curBlockCount = count;
            blockOrdinal = idx;
            posInBlock = 0;
        }

        @Override
        public byte[] next() throws IOException {
            if (curBlockTerms == null || posInBlock >= curBlockCount) {
                if (blockOrdinal + 1 >= numBlocks) {
                    current = null;
                    return null;
                }
                loadBlockFromCursor(blockOrdinal + 1);
            }
            current = curBlockTerms[posInBlock];
            currentStats = curBlockStats[posInBlock];
            posInBlock++;
            return current;
        }

        @Override
        public SeekStatus seekCeil(byte[] target) throws IOException {
            int floor = floorBlockIndex(target);
            int startBlock = Math.max(floor, 0);
            if (blockFirstTerms.length == 0) {
                current = null;
                return SeekStatus.END;
            }
            loadBlockAt(startBlock, blockPointers[startBlock]);
            for (int i = 0; i < curBlockCount; i++) {
                int cmp = compare(curBlockTerms[i], target);
                if (cmp >= 0) {
                    posInBlock = i + 1;
                    current = curBlockTerms[i];
                    currentStats = curBlockStats[i];
                    return cmp == 0 ? SeekStatus.FOUND : SeekStatus.NOT_FOUND;
                }
            }
            if (startBlock + 1 < numBlocks) {
                loadBlockFromCursor(startBlock + 1);
                current = curBlockTerms[0];
                currentStats = curBlockStats[0];
                posInBlock = 1;
                return SeekStatus.NOT_FOUND;
            }
            current = null;
            return SeekStatus.END;
        }

        @Override
        public boolean seekExact(byte[] text) throws IOException {
            return seekCeil(text) == SeekStatus.FOUND;
        }

        @Override
        public byte[] term() {
            return current;
        }

        @Override
        public int docFreq() {
            return currentStats.docFreq();
        }

        @Override
        public long totalTermFreq() {
            return currentStats.totalTermFreq();
        }

        @Override
        public PostingsEnum postings(int flags) throws IOException {
            return new PostingsReader(postingsIn, currentStats.postingsFilePointer(), flags).postings();
        }
    }
}
