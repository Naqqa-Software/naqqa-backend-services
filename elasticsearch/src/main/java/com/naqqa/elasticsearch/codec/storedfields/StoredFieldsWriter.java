package com.naqqa.elasticsearch.codec.storedfields;

import com.naqqa.elasticsearch.codec.LZ4;
import com.naqqa.elasticsearch.store.BytesDataOutput;
import com.naqqa.elasticsearch.store.CodecUtil;
import com.naqqa.elasticsearch.store.IndexOutput;

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.Deflater;

public final class StoredFieldsWriter implements Closeable {

    public static final String CODEC_NAME = "NaqqaStoredFields";
    public static final int VERSION_START = 1;
    public static final int VERSION_CURRENT = 1;

    public static final byte COMPRESSION_LZ4_FAST = 0;
    public static final byte COMPRESSION_LZ4_HIGH = 1;
    public static final byte COMPRESSION_DEFLATE_BEST = 2;

    public static final int MAX_DOCS_PER_BLOCK = 128;
    public static final int MAX_BLOCK_BYTES = 32 * 1024;

    private final IndexOutput out;
    private final byte compressionMode;
    private final List<byte[]> pendingDocs = new ArrayList<>();
    private int pendingBytes;
    private int docCount;

    private final List<Long> blockFilePointers = new ArrayList<>();
    private final List<Integer> blockDocCounts = new ArrayList<>();
    private boolean finished;

    public StoredFieldsWriter(IndexOutput out, byte compressionMode) throws IOException {
        this.out = out;
        this.compressionMode = compressionMode;
        CodecUtil.writeHeader(out, CODEC_NAME, VERSION_CURRENT);
        out.writeByte(compressionMode);
    }

    public int addDocument(byte[] serializedFields) throws IOException {
        pendingDocs.add(serializedFields);
        pendingBytes += serializedFields.length;
        int id = docCount++;
        if (pendingDocs.size() >= MAX_DOCS_PER_BLOCK || pendingBytes >= MAX_BLOCK_BYTES) {
            flushBlock();
        }
        return id;
    }

    private void flushBlock() throws IOException {
        if (pendingDocs.isEmpty()) {
            return;
        }
        long blockStart = out.getFilePointer();
        BytesDataOutput raw = new BytesDataOutput(pendingBytes + 16);
        out.writeVInt(pendingDocs.size());
        for (byte[] doc : pendingDocs) {
            out.writeVInt(doc.length);
            raw.writeBytes(doc, 0, doc.length);
        }
        byte[] uncompressed = raw.toArrayCopy();
        byte[] compressed = compress(uncompressed, compressionMode);
        out.writeVInt(uncompressed.length);
        out.writeVInt(compressed.length);
        out.writeBytes(compressed, 0, compressed.length);

        blockFilePointers.add(blockStart);
        blockDocCounts.add(pendingDocs.size());
        pendingDocs.clear();
        pendingBytes = 0;
    }

    static byte[] compress(byte[] data, byte mode) {
        switch (mode) {
            case COMPRESSION_LZ4_FAST:
                return LZ4.compressFast(data);
            case COMPRESSION_LZ4_HIGH:
                return LZ4.compressHighCompression(data);
            case COMPRESSION_DEFLATE_BEST:
                return deflate(data);
            default:
                throw new IllegalArgumentException("unknown compression mode " + mode);
        }
    }

    private static byte[] deflate(byte[] data) {
        Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION, true);
        deflater.setInput(data);
        deflater.finish();
        byte[] buffer = new byte[Math.max(64, data.length)];
        BytesDataOutput out = new BytesDataOutput(buffer.length);
        while (!deflater.finished()) {
            int n = deflater.deflate(buffer);
            out.writeBytes(buffer, 0, n);
        }
        deflater.end();
        return out.toArrayCopy();
    }

    public void finish() throws IOException {
        if (finished) {
            return;
        }
        finished = true;
        flushBlock();
        long indexOffset = out.getFilePointer();
        out.writeVInt(blockFilePointers.size());
        for (int i = 0; i < blockFilePointers.size(); i++) {
            out.writeLong(blockFilePointers.get(i));
            out.writeVInt(blockDocCounts.get(i));
        }
        out.writeLong(indexOffset);
        out.writeInt(docCount);
        CodecUtil.writeFooter(out);
    }

    @Override
    public void close() throws IOException {
        finish();
        out.close();
    }
}
