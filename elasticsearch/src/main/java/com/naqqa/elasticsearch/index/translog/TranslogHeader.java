package com.naqqa.elasticsearch.index.translog;

import com.naqqa.elasticsearch.common.io.stream.BytesStreamOutput;
import com.naqqa.elasticsearch.common.io.stream.ByteBufferStreamInput;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

final class TranslogHeader {

    static final int MAGIC = 0x544C4F47;
    static final int CURRENT_VERSION = 1;

    private final long generation;
    private final String translogUUID;
    private final int headerLength;

    TranslogHeader(long generation, String translogUUID, int headerLength) {
        this.generation = generation;
        this.translogUUID = translogUUID;
        this.headerLength = headerLength;
    }

    long generation() {
        return generation;
    }

    String translogUUID() {
        return translogUUID;
    }

    int headerLength() {
        return headerLength;
    }

    static byte[] serialize(long generation, String translogUUID) throws IOException {
        BytesStreamOutput out = new BytesStreamOutput(64);
        out.writeInt(MAGIC);
        out.writeInt(CURRENT_VERSION);
        out.writeLong(generation);
        out.writeString(translogUUID);
        return out.toByteArray();
    }

    static TranslogHeader read(FileChannel channel, long generationFromFileName) throws IOException {
        long fileLength = channel.size();
        if (fileLength < 16) {
            throw new TranslogCorruptedException("translog header truncated for generation [" + generationFromFileName + "]");
        }
        int probe = (int) Math.min(fileLength, 4096);
        ByteBuffer buffer = ByteBuffer.allocate(probe);
        readFully(channel, buffer, 0);
        buffer.flip();
        ByteBufferStreamInput in = new ByteBufferStreamInput(buffer);
        int magic;
        try {
            magic = in.readInt();
        } catch (IOException e) {
            throw new TranslogCorruptedException("translog header truncated for generation [" + generationFromFileName + "]", e);
        }
        if (magic != MAGIC) {
            throw new TranslogCorruptedException("translog header corrupted, bad magic for generation [" + generationFromFileName + "]");
        }
        int version = in.readInt();
        if (version != CURRENT_VERSION) {
            throw new TranslogCorruptedException("translog header has unsupported version [" + version + "]");
        }
        long generation = in.readLong();
        String uuid = in.readString();
        return new TranslogHeader(generation, uuid, in.position());
    }

    private static void readFully(FileChannel channel, ByteBuffer buffer, long position) throws IOException {
        long pos = position;
        while (buffer.hasRemaining()) {
            int n = channel.read(buffer, pos);
            if (n < 0) {
                throw new TranslogCorruptedException("translog header truncated");
            }
            pos += n;
        }
    }
}
