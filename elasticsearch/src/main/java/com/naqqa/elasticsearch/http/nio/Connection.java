package com.naqqa.elasticsearch.http.nio;

import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.Deque;
import java.util.concurrent.ConcurrentLinkedDeque;

final class Connection {
    final SocketChannel channel;
    SelectionKey key;
    byte[] readBuf = new byte[8192];
    int readLen = 0;
    boolean readClosed = false;
    boolean closing = false;

    final Deque<ResponseSlot> outbound = new ConcurrentLinkedDeque<>();
    final Object writeLock = new Object();
    ByteBuffer pendingWrite;
    boolean pendingWriteCloses;

    Connection(SocketChannel channel) {
        this.channel = channel;
    }
}
