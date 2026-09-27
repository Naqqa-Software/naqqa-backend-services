package com.naqqa.elasticsearch.transport;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

final class FrameDecoder {

    private byte[] buffer = new byte[8192];
    private int length = 0;

    void append(byte[] data, int offset, int len) {
        ensureCapacity(length + len);
        System.arraycopy(data, offset, buffer, length, len);
        length += len;
    }

    List<byte[]> decode() throws IOException {
        List<byte[]> frames = new ArrayList<>();
        int offset = 0;
        while (length - offset >= TcpHeader.HEADER_SIZE) {
            byte m0 = buffer[offset];
            byte m1 = buffer[offset + 1];
            if (m0 != TcpHeader.MARKER_MAGIC_0 || m1 != TcpHeader.MARKER_MAGIC_1) {
                throw new IOException("invalid transport frame: expected magic bytes ['E','S'] but got [" + (m0 & 0xFF) + "," + (m1 & 0xFF) + "]");
            }
            int frameLength = ((buffer[offset + 2] & 0xFF) << 24)
                | ((buffer[offset + 3] & 0xFF) << 16)
                | ((buffer[offset + 4] & 0xFF) << 8)
                | (buffer[offset + 5] & 0xFF);
            if (frameLength < 0 || frameLength > TcpHeader.MAX_FRAME_SIZE) {
                throw new IOException("invalid transport frame: declared length [" + frameLength + "] exceeds maximum [" + TcpHeader.MAX_FRAME_SIZE + "]");
            }
            int total = TcpHeader.HEADER_SIZE + frameLength;
            if (length - offset < total) {
                break;
            }
            byte[] body = new byte[frameLength];
            System.arraycopy(buffer, offset + TcpHeader.HEADER_SIZE, body, 0, frameLength);
            frames.add(body);
            offset += total;
        }
        if (offset > 0) {
            length -= offset;
            System.arraycopy(buffer, offset, buffer, 0, length);
        }
        return frames;
    }

    private void ensureCapacity(int min) {
        if (min > buffer.length) {
            int newCapacity = Math.max(buffer.length * 2, min);
            byte[] newBuffer = new byte[newCapacity];
            System.arraycopy(buffer, 0, newBuffer, 0, length);
            buffer = newBuffer;
        }
    }
}
