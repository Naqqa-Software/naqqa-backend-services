package com.naqqa.elasticsearch.codec.storedfields;

import com.naqqa.elasticsearch.codec.LZ4;
import com.naqqa.elasticsearch.store.CodecUtil;
import com.naqqa.elasticsearch.store.IndexInput;

import java.io.IOException;
import java.util.zip.Inflater;

public final class StoredFieldsReader {

    private final IndexInput in;
    private final byte compressionMode;
    private final int docCount;
    private final long[] blockFilePointers;
    private final int[] blockDocCounts;
    private final int[] blockBaseDocId;
    private volatile BlockCache cache;

    private record BlockCache(int blockIdx, int[] lengths, byte[] uncompressed) {
    }

    public StoredFieldsReader(IndexInput in) throws IOException {
        CodecUtil.checksumEntireFile(in);
        this.in = in;
        CodecUtil.checkHeader(in, StoredFieldsWriter.CODEC_NAME, StoredFieldsWriter.VERSION_START, StoredFieldsWriter.VERSION_CURRENT);
        this.compressionMode = in.readByte();
        long trailerPos = in.length() - CodecUtil.footerLength() - 12;
        in.seek(trailerPos);
        long indexOffset = in.readLong();
        this.docCount = in.readInt();
        in.seek(indexOffset);
        int numBlocks = in.readVInt();
        this.blockFilePointers = new long[numBlocks];
        this.blockDocCounts = new int[numBlocks];
        this.blockBaseDocId = new int[numBlocks];
        int base = 0;
        for (int i = 0; i < numBlocks; i++) {
            blockFilePointers[i] = in.readLong();
            blockDocCounts[i] = in.readVInt();
            blockBaseDocId[i] = base;
            base += blockDocCounts[i];
        }
    }

    public int docCount() {
        return docCount;
    }

    public byte[] document(int docId) throws IOException {
        if (docId < 0 || docId >= docCount) {
            throw new IllegalArgumentException("docId out of range: " + docId);
        }
        int blockIdx = findBlock(docId);
        BlockCache cached = cache;
        int[] lengths;
        byte[] uncompressed;
        if (cached != null && cached.blockIdx() == blockIdx) {
            lengths = cached.lengths();
            uncompressed = cached.uncompressed();
        } else {
            IndexInput cursor = in.clone();
            cursor.seek(blockFilePointers[blockIdx]);
            int numDocsInBlock = cursor.readVInt();
            lengths = new int[numDocsInBlock];
            for (int i = 0; i < numDocsInBlock; i++) {
                lengths[i] = cursor.readVInt();
            }
            int uncompressedLength = cursor.readVInt();
            int compressedLength = cursor.readVInt();
            byte[] compressed = new byte[compressedLength];
            cursor.readBytes(compressed, 0, compressedLength);
            uncompressed = decompress(compressed, uncompressedLength, compressionMode);
            cache = new BlockCache(blockIdx, lengths, uncompressed);
        }
        int localIndex = docId - blockBaseDocId[blockIdx];
        int offset = 0;
        for (int i = 0; i < localIndex; i++) {
            offset += lengths[i];
        }
        byte[] result = new byte[lengths[localIndex]];
        System.arraycopy(uncompressed, offset, result, 0, lengths[localIndex]);
        return result;
    }

    private int findBlock(int docId) {
        int lo = 0;
        int hi = blockBaseDocId.length - 1;
        int result = 0;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (blockBaseDocId[mid] <= docId) {
                result = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return result;
    }

    static byte[] decompress(byte[] compressed, int originalLength, byte mode) throws IOException {
        switch (mode) {
            case StoredFieldsWriter.COMPRESSION_LZ4_FAST:
            case StoredFieldsWriter.COMPRESSION_LZ4_HIGH:
                return LZ4.decompress(compressed, originalLength);
            case StoredFieldsWriter.COMPRESSION_DEFLATE_BEST:
                return inflate(compressed, originalLength);
            default:
                throw new IllegalArgumentException("unknown compression mode " + mode);
        }
    }

    private static byte[] inflate(byte[] compressed, int originalLength) throws IOException {
        Inflater inflater = new Inflater(true);
        inflater.setInput(compressed);
        byte[] result = new byte[originalLength];
        try {
            int off = 0;
            while (off < originalLength && !inflater.finished()) {
                int n = inflater.inflate(result, off, originalLength - off);
                if (n == 0 && inflater.needsInput()) {
                    break;
                }
                off += n;
            }
        } catch (java.util.zip.DataFormatException e) {
            throw new IOException(e);
        } finally {
            inflater.end();
        }
        return result;
    }
}
