package com.naqqa.elasticsearch.transport;

final class TcpHeader {

    private TcpHeader() {
    }

    static final byte MARKER_MAGIC_0 = 'E';
    static final byte MARKER_MAGIC_1 = 'S';
    static final int MARKER_BYTES_SIZE = 2;
    static final int MESSAGE_LENGTH_SIZE = 4;
    static final int HEADER_SIZE = MARKER_BYTES_SIZE + MESSAGE_LENGTH_SIZE;
    static final int MAX_FRAME_SIZE = 100 * 1024 * 1024;
}
