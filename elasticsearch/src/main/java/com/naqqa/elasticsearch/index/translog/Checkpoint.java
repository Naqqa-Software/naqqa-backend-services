package com.naqqa.elasticsearch.index.translog;

import com.naqqa.elasticsearch.common.io.stream.BytesStreamOutput;
import com.naqqa.elasticsearch.common.io.stream.ByteBufferStreamInput;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.zip.CRC32;

final class Checkpoint {

    private static final int MAGIC = 0x544C4B43;

    final long generation;
    final long minSeqNo;
    final long maxSeqNo;
    final long offset;
    final long numOps;

    Checkpoint(long generation, long minSeqNo, long maxSeqNo, long offset, long numOps) {
        this.generation = generation;
        this.minSeqNo = minSeqNo;
        this.maxSeqNo = maxSeqNo;
        this.offset = offset;
        this.numOps = numOps;
    }

    byte[] serialize() throws IOException {
        BytesStreamOutput out = new BytesStreamOutput(64);
        out.writeInt(MAGIC);
        out.writeLong(generation);
        out.writeLong(minSeqNo);
        out.writeLong(maxSeqNo);
        out.writeLong(offset);
        out.writeLong(numOps);
        byte[] body = out.toByteArray();
        CRC32 crc = new CRC32();
        crc.update(body);
        BytesStreamOutput full = new BytesStreamOutput(body.length + 8);
        full.writeBytes(body);
        full.writeLong(crc.getValue());
        return full.toByteArray();
    }

    void write(Path file) throws IOException {
        Path tmp = file.resolveSibling(file.getFileName().toString() + ".tmp");
        Files.write(tmp, serialize());
        try (FileChannel channel = FileChannel.open(tmp, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    static Checkpoint read(Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        if (bytes.length < 4 + 8 * 5) {
            throw new TranslogCorruptedException("checkpoint file truncated: " + file);
        }
        byte[] body = Arrays.copyOfRange(bytes, 0, bytes.length - 8);
        ByteBuffer crcBuf = ByteBuffer.wrap(bytes, bytes.length - 8, 8);
        long storedCrc = crcBuf.getLong();
        CRC32 crc = new CRC32();
        crc.update(body);
        if (crc.getValue() != storedCrc) {
            throw new TranslogCorruptedException("checkpoint checksum mismatch: " + file);
        }
        ByteBufferStreamInput in = new ByteBufferStreamInput(body);
        int magic = in.readInt();
        if (magic != MAGIC) {
            throw new TranslogCorruptedException("checkpoint bad magic: " + file);
        }
        long generation = in.readLong();
        long minSeqNo = in.readLong();
        long maxSeqNo = in.readLong();
        long offset = in.readLong();
        long numOps = in.readLong();
        return new Checkpoint(generation, minSeqNo, maxSeqNo, offset, numOps);
    }
}
