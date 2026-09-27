package com.naqqa.elasticsearch.transport;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.Executor;

final class TcpTransport {

    private final TransportMessageListener listener;
    private final Executor dispatchExecutor;
    private final Set<TcpChannel> openChannels = new CopyOnWriteArraySet<>();
    private volatile Selector selector;
    private volatile ServerSocketChannel serverChannel;
    private volatile Thread selectorThread;
    private volatile boolean running;
    final java.util.concurrent.atomic.LongAdder rxCount = new java.util.concurrent.atomic.LongAdder();
    final java.util.concurrent.atomic.LongAdder rxBytes = new java.util.concurrent.atomic.LongAdder();
    final java.util.concurrent.atomic.LongAdder txCount = new java.util.concurrent.atomic.LongAdder();
    final java.util.concurrent.atomic.LongAdder txBytes = new java.util.concurrent.atomic.LongAdder();

    TcpTransport(TransportMessageListener listener, Executor dispatchExecutor) {
        this.listener = listener;
        this.dispatchExecutor = dispatchExecutor;
    }

    synchronized void bind(InetSocketAddress address) throws IOException {
        selector = Selector.open();
        serverChannel = ServerSocketChannel.open();
        serverChannel.configureBlocking(false);
        serverChannel.socket().setReuseAddress(true);
        serverChannel.bind(address);
        serverChannel.register(selector, SelectionKey.OP_ACCEPT);
    }

    InetSocketAddress boundAddress() {
        try {
            return (InetSocketAddress) serverChannel.getLocalAddress();
        } catch (IOException e) {
            throw new IllegalStateException("failed to read bound address", e);
        }
    }

    synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        selectorThread = new Thread(this::selectLoop, "transport-selector");
        selectorThread.setDaemon(true);
        selectorThread.start();
    }

    synchronized void stop() {
        running = false;
        if (selector != null) {
            selector.wakeup();
        }
        Thread thread = selectorThread;
        if (thread != null) {
            try {
                thread.join(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        for (TcpChannel channel : openChannels) {
            channel.close(null);
        }
        openChannels.clear();
        try {
            if (serverChannel != null) {
                serverChannel.close();
            }
        } catch (IOException ignored) {
        }
        try {
            if (selector != null) {
                selector.close();
            }
        } catch (IOException ignored) {
        }
    }

    CompletableFuture<TcpChannel> openChannel(InetSocketAddress address) {
        CompletableFuture<TcpChannel> future = new CompletableFuture<>();
        try {
            SocketChannel socketChannel = SocketChannel.open();
            socketChannel.configureBlocking(false);
            socketChannel.setOption(StandardSocketOptions.TCP_NODELAY, true);
            socketChannel.connect(address);
            SelectionKey key = socketChannel.register(selector, SelectionKey.OP_CONNECT);
            TcpChannel channel = new TcpChannel(socketChannel, key, false, address, future);
            key.attach(channel);
            openChannels.add(channel);
            channel.addCloseListener(cause -> openChannels.remove(channel));
            selector.wakeup();
        } catch (IOException e) {
            future.completeExceptionally(e);
        }
        return future;
    }

    void sendBytes(TcpChannel channel, byte[] frame) {
        try {
            txCount.increment();
            txBytes.add(frame.length);
            channel.enqueueWrite(frame);
        } catch (Exception e) {
            channel.close(e);
        }
    }

    private void selectLoop() {
        while (running) {
            int n;
            try {
                n = selector.select(500);
            } catch (IOException e) {
                continue;
            }
            if (n == 0) {
                continue;
            }
            Iterator<SelectionKey> it = selector.selectedKeys().iterator();
            while (it.hasNext()) {
                SelectionKey key = it.next();
                it.remove();
                try {
                    if (!key.isValid()) {
                        continue;
                    }
                    if (key.isAcceptable()) {
                        doAccept();
                        continue;
                    }
                    if (key.isConnectable()) {
                        doConnect(key);
                    }
                    if (key.isValid() && key.isReadable()) {
                        doRead(key);
                    }
                    if (key.isValid() && key.isWritable()) {
                        doWrite(key);
                    }
                } catch (CancelledKeyException ignored) {
                } catch (Exception e) {
                    TcpChannel channel = (TcpChannel) key.attachment();
                    if (channel != null) {
                        openChannels.remove(channel);
                        channel.close(e);
                    }
                }
            }
        }
    }

    private void doAccept() throws IOException {
        SocketChannel socketChannel = serverChannel.accept();
        if (socketChannel == null) {
            return;
        }
        socketChannel.configureBlocking(false);
        socketChannel.setOption(StandardSocketOptions.TCP_NODELAY, true);
        SelectionKey channelKey = socketChannel.register(selector, SelectionKey.OP_READ);
        InetSocketAddress remote = (InetSocketAddress) socketChannel.getRemoteAddress();
        TcpChannel channel = new TcpChannel(socketChannel, channelKey, true, remote, CompletableFuture.completedFuture(null));
        channelKey.attach(channel);
        openChannels.add(channel);
        channel.addCloseListener(cause -> openChannels.remove(channel));
    }

    private void doConnect(SelectionKey key) {
        SocketChannel socketChannel = (SocketChannel) key.channel();
        TcpChannel channel = (TcpChannel) key.attachment();
        try {
            if (socketChannel.finishConnect()) {
                key.interestOps(SelectionKey.OP_READ);
                channel.markConnected();
            }
        } catch (IOException e) {
            key.cancel();
            channel.close(e);
        }
    }

    private void doRead(SelectionKey key) {
        SocketChannel socketChannel = (SocketChannel) key.channel();
        TcpChannel channel = (TcpChannel) key.attachment();
        ByteBuffer buffer = ByteBuffer.allocate(65536);
        int n;
        try {
            n = socketChannel.read(buffer);
        } catch (IOException e) {
            channel.close(e);
            return;
        }
        if (n < 0) {
            channel.close(null);
            return;
        }
        if (n == 0) {
            return;
        }
        channel.updateLastRead();
        buffer.flip();
        byte[] data = new byte[n];
        buffer.get(data);
        channel.decoder().append(data, 0, n);
        List<byte[]> frames;
        try {
            frames = channel.decoder().decode();
        } catch (IOException e) {
            channel.close(e);
            return;
        }
        for (byte[] body : frames) {
            dispatch(channel, body);
        }
    }

    private void dispatch(TcpChannel channel, byte[] body) {
        Runnable task = () -> {
            try {
                MessageCodec.DecodedMessage message = MessageCodec.decodeBody(body);
                rxCount.increment();
                rxBytes.add(body.length);
                listener.onMessage(channel, message.requestId(), message.status(), message.version(), message.action(), message.payload());
            } catch (Exception e) {
                System.err.println("transport: failed to decode inbound message: " + e);
            }
        };
        try {
            dispatchExecutor.execute(task);
        } catch (RuntimeException e) {
            task.run();
        }
    }

    private void doWrite(SelectionKey key) {
        TcpChannel channel = (TcpChannel) key.attachment();
        try {
            boolean drained = channel.flush();
            if (drained) {
                key.interestOpsAnd(~SelectionKey.OP_WRITE);
            }
        } catch (IOException e) {
            channel.close(e);
        }
    }
}
