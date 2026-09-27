package com.naqqa.elasticsearch.index.translog;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

final class TranslogReader extends BaseTranslogReader {

    private final Path path;
    private final long length;
    private final long minSeqNo;
    private final long maxSeqNo;
    private final long opCount;

    TranslogReader(FileChannel channel, Path path, long generation, int headerLength, long length, long minSeqNo, long maxSeqNo, long opCount) {
        super(channel, generation, headerLength);
        this.path = path;
        this.length = length;
        this.minSeqNo = minSeqNo;
        this.maxSeqNo = maxSeqNo;
        this.opCount = opCount;
    }

    @Override
    protected long endOffset() {
        return length;
    }

    Path path() {
        return path;
    }

    long length() {
        return length;
    }

    long minSeqNo() {
        return minSeqNo;
    }

    long maxSeqNo() {
        return maxSeqNo;
    }

    long opCount() {
        return opCount;
    }

    TranslogWriter toWriter() {
        return TranslogWriter.openForRecovery(channel, path, generation, headerLength, length, minSeqNo, maxSeqNo, opCount);
    }

    void close() throws IOException {
        channel.close();
    }

    static TranslogReader openAndRecover(Path file, long expectedGeneration) throws IOException {
        FileChannel channel = FileChannel.open(file, StandardOpenOption.READ, StandardOpenOption.WRITE);
        TranslogHeader header;
        try {
            header = TranslogHeader.read(channel, expectedGeneration);
        } catch (IOException e) {
            channel.close();
            throw e;
        }
        if (header.generation() != expectedGeneration) {
            channel.close();
            throw new TranslogCorruptedException("generation mismatch, expected [" + expectedGeneration + "] but header says [" + header.generation() + "] for file " + file);
        }
        long fileLength = channel.size();
        TranslogScanner.ScanResult result = TranslogScanner.scan(channel, header.headerLength(), fileLength);
        if (result.endPosition() < fileLength) {
            channel.truncate(result.endPosition());
            channel.force(true);
        }
        boolean hasOps = !result.operations().isEmpty();
        long minSeqNo = hasOps ? result.minSeqNo() : -1L;
        long maxSeqNo = hasOps ? result.maxSeqNo() : -1L;
        return new TranslogReader(channel, file, header.generation(), header.headerLength(), result.endPosition(), minSeqNo, maxSeqNo, result.operations().size());
    }
}
