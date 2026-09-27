package com.naqqa.elasticsearch.transport;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

final class TcpChannel {

    private final SocketChannel socketChannel;
    private final SelectionKey key;
    private final boolean serverChannel;
    private final InetSocketAddress remoteAddress;
    private final FrameDecoder decoder = new FrameDecoder();
    private final Deque<ByteBuffer> writeQueue = new ConcurrentLinkedDeque<>();
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final CompletableFuture<TcpChannel> connectFuture;
    private final List<Consumer<Exception>> closeListeners = new CopyOnWriteArrayList<>();
    private volatile long lastReadNanos = System.nanoTime();

    TcpChannel(SocketChannel socketChannel, SelectionKey key, boolean serverChannel, InetSocketAddress remoteAddress, CompletableFuture<TcpChannel> connectFuture) {
        this.socketChannel = socketChannel;
        this.key = key;
        this.serverChannel = serverChannel;
        this.remoteAddress = remoteAddress;
        this.connectFuture = connectFuture;
    }

    SocketChannel socketChannel() {
        return socketChannel;
    }

    boolean isServerChannel() {
        return serverChannel;
    }

    InetSocketAddress remoteAddress() {
        return remoteAddress;
    }

    FrameDecoder decoder() {
        return decoder;
    }

    boolean isOpen() {
        return !closed.get();
    }

    void markConnected() {
        connectFuture.complete(this);
    }

    void failConnect(Exception cause) {
        connectFuture.completeExceptionally(cause);
    }

    void updateLastRead() {
        lastReadNanos = System.nanoTime();
    }

    long idleNanos() {
        return System.nanoTime() - lastReadNanos;
    }

    void enqueueWrite(byte[] data) {
        if (!isOpen()) {
            return;
        }
        writeQueue.add(ByteBuffer.wrap(data));
        key.interestOpsOr(SelectionKey.OP_WRITE);
        key.selector().wakeup();
    }

    boolean flush() throws IOException {
        ByteBuffer buffer;
        while ((buffer = writeQueue.peek()) != null) {
            socketChannel.write(buffer);
            if (buffer.hasRemaining()) {
                return false;
            }
            writeQueue.poll();
        }
        return true;
    }

    void addCloseListener(Consumer<Exception> listener) {
        closeListeners.add(listener);
    }

    void close(Exception cause) {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        if (!connectFuture.isDone()) {
            connectFuture.completeExceptionally(cause != null ? cause : new IOException("channel closed"));
        }
        key.cancel();
        try {
            socketChannel.close();
        } catch (IOException ignored) {
        }
        for (Consumer<Exception> listener : closeListeners) {
            listener.accept(cause);
        }
    }
}
