package com.naqqa.elasticsearch.index.translog;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;

final class TranslogScanner {

    record ScanResult(List<Operation> operations, long endPosition, long minSeqNo, long maxSeqNo) {
    }

    private TranslogScanner() {
    }

    static ScanResult scan(FileChannel channel, long fromPosition, long limit) throws IOException {
        List<Operation> ops = new ArrayList<>();
        long pos = fromPosition;
        long minSeqNo = Long.MAX_VALUE;
        long maxSeqNo = Long.MIN_VALUE;
        while (true) {
            if (pos + 4 > limit) {
                break;
            }
            try {
                ByteBuffer lenBuf = ByteBuffer.allocate(4);
                readFully(channel, lenBuf, pos);
                lenBuf.flip();
                int n = lenBuf.getInt();
                if (n < 0 || pos + 4L + n + 4L > limit) {
                    break;
                }
                ByteBuffer payloadBuf = ByteBuffer.allocate(n);
                readFully(channel, payloadBuf, pos + 4);
                byte[] payload = payloadBuf.array();
                ByteBuffer crcBuf = ByteBuffer.allocate(4);
                readFully(channel, crcBuf, pos + 4L + n);
                crcBuf.flip();
                int storedCrc = crcBuf.getInt();
                CRC32 crc = new CRC32();
                crc.update(payload);
                if ((int) crc.getValue() != storedCrc) {
                    break;
                }
                Operation op;
                try {
                    op = OperationCodec.decode(payload);
                } catch (Exception e) {
                    break;
                }
                ops.add(op);
                if (op.seqNo() < minSeqNo) {
                    minSeqNo = op.seqNo();
                }
                if (op.seqNo() > maxSeqNo) {
                    maxSeqNo = op.seqNo();
                }
                pos += 4L + n + 4L;
            } catch (EOFException e) {
                break;
            }
        }
        return new ScanResult(ops, pos, minSeqNo, maxSeqNo);
    }

    private static void readFully(FileChannel channel, ByteBuffer buf, long position) throws IOException {
        long pos = position;
        while (buf.hasRemaining()) {
            int n = channel.read(buf, pos);
            if (n < 0) {
                throw new EOFException();
            }
            pos += n;
        }
    }
}
