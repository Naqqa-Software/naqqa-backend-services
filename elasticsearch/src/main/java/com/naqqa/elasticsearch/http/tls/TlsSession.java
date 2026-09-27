package com.naqqa.elasticsearch.http.tls;

import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLSession;

public final class TlsSession implements Closeable {

    private static final ByteBuffer EMPTY = ByteBuffer.allocate(0);

    private final SSLEngine engine;
    private final SocketChannel channel;
    private ByteBuffer netIn;
    private ByteBuffer netOut;
    private ByteBuffer appIn;
    private volatile boolean handshakeComplete = false;

    public TlsSession(SSLEngine engine, SocketChannel channel) {
        this.engine = engine;
        this.channel = channel;
        SSLSession session = engine.getSession();
        this.netIn = ByteBuffer.allocate(session.getPacketBufferSize());
        this.netOut = ByteBuffer.allocate(session.getPacketBufferSize());
        this.appIn = ByteBuffer.allocate(session.getApplicationBufferSize());
    }

    public boolean isHandshakeComplete() {
        return handshakeComplete;
    }

    public boolean handshake() throws IOException {
        if (handshakeComplete) {
            return true;
        }
        SSLEngineResult.HandshakeStatus status = engine.getHandshakeStatus();
        if (status == SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING) {
            engine.beginHandshake();
            status = engine.getHandshakeStatus();
        }
        while (true) {
            switch (status) {
                case NEED_UNWRAP, NEED_UNWRAP_AGAIN: {
                    if (status == SSLEngineResult.HandshakeStatus.NEED_UNWRAP) {
                        int n = channel.read(netIn);
                        if (n < 0) {
                            throw new EOFException("channel closed during TLS handshake");
                        }
                        if (n == 0 && netIn.position() == 0) {
                            return false;
                        }
                    }
                    netIn.flip();
                    appIn = growIfNeeded(appIn, engine.getSession().getApplicationBufferSize());
                    SSLEngineResult result;
                    try {
                        result = engine.unwrap(netIn, appIn);
                    } finally {
                        netIn.compact();
                    }
                    if (result.getStatus() == SSLEngineResult.Status.BUFFER_UNDERFLOW) {
                        netIn = growIfNeeded(netIn, engine.getSession().getPacketBufferSize());
                        return false;
                    }
                    if (result.getStatus() == SSLEngineResult.Status.CLOSED) {
                        throw new IOException("TLS closed during handshake");
                    }
                    status = result.getHandshakeStatus();
                    break;
                }
                case NEED_WRAP: {
                    netOut.clear();
                    SSLEngineResult result = engine.wrap(EMPTY, netOut);
                    status = result.getHandshakeStatus();
                    netOut.flip();
                    while (netOut.hasRemaining()) {
                        if (channel.write(netOut) == 0) {
                            Thread.onSpinWait();
                        }
                    }
                    break;
                }
                case NEED_TASK: {
                    Runnable task;
                    while ((task = engine.getDelegatedTask()) != null) {
                        task.run();
                    }
                    status = engine.getHandshakeStatus();
                    break;
                }
                case FINISHED:
                case NOT_HANDSHAKING: {
                    handshakeComplete = true;
                    return true;
                }
                default:
                    throw new IOException("unexpected handshake status " + status);
            }
        }
    }

    public int read(ByteBuffer dst) throws IOException {
        if (!handshakeComplete && !handshake()) {
            return 0;
        }
        appIn.flip();
        if (appIn.hasRemaining()) {
            int copied = copy(appIn, dst);
            appIn.compact();
            return copied;
        }
        appIn.compact();
        int n = channel.read(netIn);
        if (n < 0) {
            return -1;
        }
        if (n == 0) {
            return 0;
        }
        netIn.flip();
        SSLEngineResult result;
        try {
            result = engine.unwrap(netIn, appIn);
        } finally {
            netIn.compact();
        }
        if (result.getStatus() == SSLEngineResult.Status.CLOSED) {
            return -1;
        }
        appIn.flip();
        int copied = copy(appIn, dst);
        appIn.compact();
        return copied;
    }

    public void write(ByteBuffer src) throws IOException {
        if (!handshakeComplete) {
            handshake();
        }
        while (src.hasRemaining()) {
            netOut.clear();
            SSLEngineResult result = engine.wrap(src, netOut);
            netOut.flip();
            while (netOut.hasRemaining()) {
                if (channel.write(netOut) == 0) {
                    Thread.onSpinWait();
                }
            }
            if (result.getStatus() == SSLEngineResult.Status.CLOSED) {
                throw new IOException("TLS session closed");
            }
        }
    }

    public void closeOutbound() throws IOException {
        if (engine.isOutboundDone()) {
            return;
        }
        engine.closeOutbound();
        while (!engine.isOutboundDone()) {
            netOut.clear();
            SSLEngineResult result = engine.wrap(EMPTY, netOut);
            netOut.flip();
            while (netOut.hasRemaining()) {
                if (channel.write(netOut) == 0) {
                    Thread.onSpinWait();
                }
            }
            if (result.getStatus() == SSLEngineResult.Status.CLOSED) {
                break;
            }
        }
    }

    @Override
    public void close() throws IOException {
        try {
            closeOutbound();
        } finally {
            channel.close();
        }
    }

    private static ByteBuffer growIfNeeded(ByteBuffer buffer, int minCapacity) {
        if (buffer.capacity() >= minCapacity) {
            return buffer;
        }
        ByteBuffer bigger = ByteBuffer.allocate(minCapacity);
        buffer.flip();
        bigger.put(buffer);
        return bigger;
    }

    private static int copy(ByteBuffer src, ByteBuffer dst) {
        int n = Math.min(src.remaining(), dst.remaining());
        if (n <= 0) {
            return 0;
        }
        ByteBuffer slice = src.slice();
        slice.limit(n);
        dst.put(slice);
        src.position(src.position() + n);
        return n;
    }
}
