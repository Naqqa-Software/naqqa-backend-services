package com.naqqa.elasticsearch.http.nio;

import com.naqqa.elasticsearch.http.CorsHandler;
import com.naqqa.elasticsearch.http.HttpHeaders;
import com.naqqa.elasticsearch.http.HttpServerConfig;
import com.naqqa.elasticsearch.http.HttpServerTransport;
import com.naqqa.elasticsearch.http.RestErrors;
import com.naqqa.elasticsearch.http.RestMethod;
import com.naqqa.elasticsearch.http.RestRequest;
import com.naqqa.elasticsearch.http.RestResponse;
import com.naqqa.elasticsearch.http.RouteResult;
import com.naqqa.elasticsearch.http.Router;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class NioHttpServerTransport implements HttpServerTransport {

    private final HttpServerConfig config;
    private final Router router;
    private final CorsHandler corsHandler;

    private ServerSocketChannel serverChannel;
    private Selector selector;
    private Thread ioThread;
    private ExecutorService workerExecutor;
    private ExecutorService tlsExecutor;
    private volatile boolean running;

    private final ConcurrentLinkedQueue<Connection> readyToWrite = new ConcurrentLinkedQueue<>();
    private final Set<Connection> allConnections = ConcurrentHashMap.newKeySet();

    public NioHttpServerTransport(HttpServerConfig config, Router router) {
        this.config = config;
        this.router = router;
        this.corsHandler = new CorsHandler(config.cors());
    }

    @Override
    public void start() throws IOException {
        serverChannel = ServerSocketChannel.open();
        serverChannel.configureBlocking(false);
        serverChannel.bind(new InetSocketAddress(config.host(), config.port()), config.backlog());
        selector = Selector.open();
        serverChannel.register(selector, SelectionKey.OP_ACCEPT);
        workerExecutor = Executors.newVirtualThreadPerTaskExecutor();
        if (config.sslContext() != null) {
            tlsExecutor = Executors.newVirtualThreadPerTaskExecutor();
        }
        running = true;
        ioThread = new Thread(this::loop, "nio-http-io");
        ioThread.setDaemon(true);
        ioThread.start();
    }

    @Override
    public InetSocketAddress boundAddress() {
        return (InetSocketAddress) serverChannel.socket().getLocalSocketAddress();
    }

    @Override
    public void close() {
        running = false;
        if (selector != null) {
            selector.wakeup();
        }
        try {
            if (ioThread != null) {
                ioThread.join(3000);
            }
        } catch (InterruptedException ignored) {
        }
        for (Connection connection : allConnections) {
            closeConnection(connection);
        }
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
        if (workerExecutor != null) {
            workerExecutor.shutdown();
        }
        if (tlsExecutor != null) {
            tlsExecutor.shutdown();
        }
    }

    private void loop() {
        while (running) {
            try {
                selector.select(500);
            } catch (IOException e) {
                break;
            }
            drainReadyToWrite();
            if (!running) {
                break;
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
                    } else {
                        Connection connection = (Connection) key.attachment();
                        if (key.isValid() && key.isReadable()) {
                            doRead(connection);
                        }
                        if (key.isValid() && key.isWritable()) {
                            tryFlush(connection);
                        }
                    }
                } catch (Exception e) {
                    if (key.attachment() instanceof Connection connection) {
                        closeConnection(connection);
                    }
                }
            }
        }
        long deadline = System.currentTimeMillis() + 1000;
        while (System.currentTimeMillis() < deadline && anyPendingWrites()) {
            drainReadyToWrite();
            for (Connection connection : allConnections) {
                tryFlush(connection);
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException ignored) {
                break;
            }
        }
    }

    private boolean anyPendingWrites() {
        for (Connection connection : allConnections) {
            if (!connection.outbound.isEmpty() || connection.pendingWrite != null) {
                return true;
            }
        }
        return false;
    }

    private void drainReadyToWrite() {
        Connection connection;
        while ((connection = readyToWrite.poll()) != null) {
            tryFlush(connection);
        }
    }

    private void doAccept() throws IOException {
        SocketChannel client = serverChannel.accept();
        if (client == null) {
            return;
        }
        if (config.sslContext() != null) {
            client.configureBlocking(true);
            tlsExecutor.execute(new TlsConnectionHandler(this, client));
        } else {
            client.configureBlocking(false);
            Connection connection = new Connection(client);
            SelectionKey key = client.register(selector, SelectionKey.OP_READ, connection);
            connection.key = key;
            allConnections.add(connection);
        }
    }

    private void doRead(Connection connection) {
        if (connection.readClosed) {
            return;
        }
        try {
            ensureCapacity(connection, 8192);
            ByteBuffer buffer = ByteBuffer.wrap(connection.readBuf, connection.readLen, connection.readBuf.length - connection.readLen);
            int n = connection.channel.read(buffer);
            if (n < 0) {
                connection.readClosed = true;
                if (connection.outbound.isEmpty() && connection.pendingWrite == null) {
                    closeConnection(connection);
                }
                return;
            }
            if (n == 0) {
                return;
            }
            connection.readLen += n;
            parseAvailable(connection);
        } catch (IOException e) {
            closeConnection(connection);
        }
    }

    private void parseAvailable(Connection connection) {
        while (true) {
            ParsedRequest parsed;
            try {
                parsed = HttpRequestParser.tryParse(connection.readBuf, connection.readLen, config);
            } catch (HttpParseException e) {
                handleParseError(connection, e);
                return;
            }
            if (parsed == null) {
                if (connection.readLen >= maxBufferCeiling()) {
                    handleParseError(connection, new HttpParseException(400, "request too large"));
                }
                return;
            }
            consume(connection, parsed.consumedLength);
            dispatch(connection, parsed);
        }
    }

    private long maxBufferCeiling() {
        return config.maxBodyBytes() + config.maxHeaderBytes() + config.maxInitialLineLength() + 65536L;
    }

    private void ensureCapacity(Connection connection, int extra) {
        if (connection.readLen + extra > connection.readBuf.length) {
            long ceiling = maxBufferCeiling();
            int newCap = (int) Math.min(ceiling, Math.max((long) connection.readBuf.length * 2, connection.readLen + extra));
            if (newCap <= connection.readBuf.length) {
                return;
            }
            byte[] bigger = new byte[newCap];
            System.arraycopy(connection.readBuf, 0, bigger, 0, connection.readLen);
            connection.readBuf = bigger;
        }
    }

    private void consume(Connection connection, int n) {
        int remaining = connection.readLen - n;
        if (remaining > 0) {
            System.arraycopy(connection.readBuf, n, connection.readBuf, 0, remaining);
        }
        connection.readLen = remaining;
    }

    private void handleParseError(Connection connection, HttpParseException error) {
        connection.readClosed = true;
        RestRequest fallback = new RestRequest(RestMethod.GET, "/", "/", Map.of(), new HttpHeaders(), new byte[0]);
        RestResponse response = RestErrors.fromException(fallback, config.errorRenderer(), error);
        ResponseSlot slot = new ResponseSlot();
        connection.outbound.add(slot);
        ResponseBuilder.Encoded encoded = ResponseBuilder.encode(fallback, response, "HTTP/1.1", config, corsHandler);
        slot.bytes = encoded.bytes();
        slot.closeConnection = true;
        slot.ready = true;
        scheduleWrite(connection);
    }

    private void dispatch(Connection connection, ParsedRequest parsed) {
        ResponseSlot slot = new ResponseSlot();
        connection.outbound.add(slot);
        workerExecutor.execute(() -> processRequest(connection, slot, parsed));
    }

    private void processRequest(Connection connection, ResponseSlot slot, ParsedRequest parsed) {
        RestRequest request;
        try {
            request = RequestFactory.build(parsed.method, parsed.rawTarget, parsed.headers, parsed.body);
        } catch (Exception e) {
            RestRequest fallback = new RestRequest(RestMethod.GET, "/", parsed.rawTarget, Map.of(), parsed.headers, new byte[0]);
            RestResponse response = RestErrors.fromException(fallback, config.errorRenderer(), e);
            fillSlot(connection, slot, fallback, response, parsed.version, true);
            return;
        }
        NioRestChannel channel = new NioRestChannel(this, connection, slot, request, parsed.version);
        try {
            var preflight = corsHandler.handlePreflight(request);
            if (preflight.isPresent()) {
                channel.sendResponse(preflight.get());
                return;
            }
            RouteResult result = router.route(request.method(), request.path());
            switch (result.outcome()) {
                case MATCHED -> {
                    request.setPathParams(result.pathParams());
                    try {
                        result.handler().handleRequest(request, channel);
                    } catch (Exception e) {
                        channel.sendResponse(RestErrors.fromException(request, config.errorRenderer(), e));
                    }
                }
                case METHOD_NOT_ALLOWED -> channel.sendResponse(RestErrors.methodNotAllowed(request, result.allowedMethods()));
                default -> channel.sendResponse(RestErrors.noHandlerFound(request, request.rawUri(), request.method()));
            }
        } catch (Exception e) {
            channel.sendResponse(RestErrors.fromException(request, config.errorRenderer(), e));
        }
    }

    private void fillSlot(Connection connection, ResponseSlot slot, RestRequest request, RestResponse response, String version, boolean forceClose) {
        ResponseBuilder.Encoded encoded = ResponseBuilder.encode(request, response, version, config, corsHandler);
        slot.bytes = encoded.bytes();
        slot.closeConnection = forceClose || encoded.close();
        slot.ready = true;
        scheduleWrite(connection);
    }

    void scheduleWrite(Connection connection) {
        readyToWrite.add(connection);
        if (selector != null) {
            selector.wakeup();
        }
    }

    private void tryFlush(Connection connection) {
        synchronized (connection.writeLock) {
            try {
                while (true) {
                    if (connection.pendingWrite != null) {
                        connection.channel.write(connection.pendingWrite);
                        if (connection.pendingWrite.hasRemaining()) {
                            setInterest(connection, true);
                            return;
                        }
                        boolean shouldClose = connection.pendingWriteCloses;
                        connection.pendingWrite = null;
                        if (shouldClose) {
                            closeConnection(connection);
                            return;
                        }
                    }
                    ResponseSlot head = connection.outbound.peek();
                    if (head == null || !head.ready) {
                        setInterest(connection, false);
                        if (head == null && connection.readClosed) {
                            closeConnection(connection);
                        }
                        return;
                    }
                    connection.outbound.poll();
                    connection.pendingWrite = ByteBuffer.wrap(head.bytes);
                    connection.pendingWriteCloses = head.closeConnection;
                }
            } catch (IOException e) {
                closeConnection(connection);
            }
        }
    }

    private void setInterest(Connection connection, boolean wantWrite) {
        if (connection.key == null || !connection.key.isValid()) {
            return;
        }
        try {
            int ops = wantWrite ? SelectionKey.OP_WRITE : 0;
            if (!connection.readClosed) {
                ops |= SelectionKey.OP_READ;
            }
            connection.key.interestOps(ops);
        } catch (java.nio.channels.CancelledKeyException ignored) {
        }
    }

    private void closeConnection(Connection connection) {
        if (connection.closing) {
            return;
        }
        connection.closing = true;
        allConnections.remove(connection);
        try {
            if (connection.key != null) {
                connection.key.cancel();
            }
            connection.channel.close();
        } catch (IOException ignored) {
        }
    }

    HttpServerConfig config() {
        return config;
    }

    CorsHandler corsHandler() {
        return corsHandler;
    }

    Router router() {
        return router;
    }
}
