package com.naqqa.elasticsearch.index.translog;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.CRC32;

final class TranslogWriter extends BaseTranslogReader {

    private final Path path;
    private final Object writeLock = new Object();
    private final AtomicLong writtenOffset;
    private volatile long syncedOffset;
    private final AtomicLong minSeqNo = new AtomicLong(Long.MAX_VALUE);
    private final AtomicLong maxSeqNo = new AtomicLong(Long.MIN_VALUE);
    private final AtomicLong opCounter = new AtomicLong();
    private final AtomicLong fsyncCount = new AtomicLong();

    private TranslogWriter(FileChannel channel, Path path, long generation, int headerLength) {
        super(channel, generation, headerLength);
        this.path = path;
        this.writtenOffset = new AtomicLong(headerLength);
        this.syncedOffset = headerLength;
    }

    static TranslogWriter create(Path file, long generation, String translogUUID) throws IOException {
        FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.READ, StandardOpenOption.WRITE);
        byte[] header = TranslogHeader.serialize(generation, translogUUID);
        ByteBuffer buf = ByteBuffer.wrap(header);
        long pos = 0;
        while (buf.hasRemaining()) {
            pos += channel.write(buf, pos);
        }
        channel.force(true);
        return new TranslogWriter(channel, file, generation, header.length);
    }

    static TranslogWriter openForRecovery(FileChannel channel, Path path, long generation, int headerLength,
                                           long writtenOffset, long minSeqNo, long maxSeqNo, long opCount) {
        TranslogWriter writer = new TranslogWriter(channel, path, generation, headerLength);
        writer.writtenOffset.set(writtenOffset);
        writer.syncedOffset = writtenOffset;
        if (opCount > 0) {
            writer.minSeqNo.set(minSeqNo);
            writer.maxSeqNo.set(maxSeqNo);
        }
        writer.opCounter.set(opCount);
        return writer;
    }

    @Override
    protected long endOffset() {
        return writtenOffset.get();
    }

    Path path() {
        return path;
    }

    Translog.Location add(Operation op) throws IOException {
        byte[] payload = OperationCodec.encode(op);
        int recordSize = 4 + payload.length + 4;
        long position;
        synchronized (writeLock) {
            position = writtenOffset.get();
            ByteBuffer buf = ByteBuffer.allocate(recordSize);
            buf.putInt(payload.length);
            buf.put(payload);
            CRC32 crc = new CRC32();
            crc.update(payload);
            buf.putInt((int) crc.getValue());
            buf.flip();
            writeFully(buf, position);
            writtenOffset.set(position + recordSize);
            opCounter.incrementAndGet();
            minSeqNo.accumulateAndGet(op.seqNo(), Math::min);
            maxSeqNo.accumulateAndGet(op.seqNo(), Math::max);
        }
        return new Translog.Location(generation, position, recordSize);
    }

    private void writeFully(ByteBuffer buf, long position) throws IOException {
        long pos = position;
        while (buf.hasRemaining()) {
            pos += channel.write(buf, pos);
        }
    }

    boolean syncNeeded() {
        return syncedOffset < writtenOffset.get();
    }

    void sync() throws IOException {
        long currentWritten = writtenOffset.get();
        if (syncedOffset >= currentWritten) {
            return;
        }
        channel.force(false);
        syncedOffset = currentWritten;
        fsyncCount.incrementAndGet();
    }

    long sizeInBytes() {
        return writtenOffset.get();
    }

    long minSeqNo() {
        return opCounter.get() == 0 ? -1L : minSeqNo.get();
    }

    long maxSeqNo() {
        return opCounter.get() == 0 ? -1L : maxSeqNo.get();
    }

    long opCount() {
        return opCounter.get();
    }

    long fsyncCount() {
        return fsyncCount.get();
    }

    TranslogReader freeze() throws IOException {
        sync();
        return new TranslogReader(channel, path, generation, headerLength, writtenOffset.get(), minSeqNo(), maxSeqNo(), opCounter.get());
    }

    void close() throws IOException {
        sync();
        channel.close();
    }
}
