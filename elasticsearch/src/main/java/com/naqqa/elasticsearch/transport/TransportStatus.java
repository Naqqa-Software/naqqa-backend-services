package com.naqqa.elasticsearch.transport;

final class TransportStatus {

    private TransportStatus() {
    }

    static final byte REQUEST = 1 << 0;
    static final byte ERROR = 1 << 1;
    static final byte COMPRESS = 1 << 2;
    static final byte HANDSHAKE = 1 << 3;

    static boolean isRequest(byte status) {
        return (status & REQUEST) != 0;
    }

    static boolean isError(byte status) {
        return (status & ERROR) != 0;
    }

    static boolean isCompress(byte status) {
        return (status & COMPRESS) != 0;
    }

    static boolean isHandshake(byte status) {
        return (status & HANDSHAKE) != 0;
    }

    static byte setRequest(byte status) {
        return (byte) (status | REQUEST);
    }

    static byte setError(byte status) {
        return (byte) (status | ERROR);
    }

    static byte setCompress(byte status) {
        return (byte) (status | COMPRESS);
    }

    static byte setHandshake(byte status) {
        return (byte) (status | HANDSHAKE);
    }
}
