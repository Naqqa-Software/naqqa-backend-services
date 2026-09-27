package com.naqqa.elasticsearch.transport;

import com.naqqa.elasticsearch.common.io.stream.ByteBufferStreamInput;
import com.naqqa.elasticsearch.common.io.stream.Writeable;
import com.naqqa.elasticsearch.common.lifecycle.AbstractLifecycleComponent;
import com.naqqa.elasticsearch.common.threadpool.ThreadPool;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

public final class TransportService extends AbstractLifecycleComponent {

    public static final String HANDSHAKE_ACTION_NAME = "internal:transport/handshake";
    public static final String PING_ACTION_NAME = "internal:transport/ping";

    private final String nodeId;
    private final InetSocketAddress bindAddress;
    private final ThreadPool threadPool;
    private final TransportSettings settings;

    private final Map<String, RequestHandlerRegistry<?>> requestHandlers = new ConcurrentHashMap<>();
    private final Map<Long, ResponseContext<?>> pendingRequests = new ConcurrentHashMap<>();
    private final Set<NodeChannels> openConnections = new CopyOnWriteArraySet<>();
    private final AtomicLong requestIdGenerator = new AtomicLong();
    private final AtomicLong pingsReceived = new AtomicLong();

    private volatile TcpTransport transport;
    private volatile DiscoveryNode localNode;
    private volatile ScheduledExecutorService scheduler;
    private volatile ScheduledFuture<?> keepAliveFuture;

    public TransportService(String nodeId, InetSocketAddress bindAddress, ThreadPool threadPool) {
        this(nodeId, bindAddress, threadPool, TransportSettings.defaults());
    }

    public TransportService(String nodeId, InetSocketAddress bindAddress, ThreadPool threadPool, TransportSettings settings) {
        this.nodeId = nodeId;
        this.bindAddress = bindAddress;
        this.threadPool = threadPool;
        this.settings = settings;
    }

    public DiscoveryNode localNode() {
        return localNode;
    }

    public InetSocketAddress boundAddress() {
        return transport.boundAddress();
    }

    public long pingsReceived() {
        return pingsReceived.get();
    }

    public <T extends TransportRequest> void registerRequestHandler(String action, Writeable.Reader<T> requestReader, TransportRequestHandler<T> handler) {
        requestHandlers.put(action, new RequestHandlerRegistry<>(action, requestReader, handler));
    }

    public Connection connectToNode(DiscoveryNode node, ConnectionProfile profile) {
        ensureStarted();
        Map<ConnectionProfile.ChannelType, TcpChannel[]> channelsByType = new EnumMap<>(ConnectionProfile.ChannelType.class);
        List<TcpChannel> opened = new ArrayList<>();
        try {
            for (Map.Entry<ConnectionProfile.ChannelType, Integer> entry : profile.connectionsPerType().entrySet()) {
                int count = entry.getValue();
                TcpChannel[] channels = new TcpChannel[count];
                for (int i = 0; i < count; i++) {
                    TcpChannel channel = transport.openChannel(node.address()).get(profile.connectTimeoutMillis(), TimeUnit.MILLISECONDS);
                    channels[i] = channel;
                    opened.add(channel);
                }
                channelsByType.put(entry.getKey(), channels);
            }
        } catch (Exception e) {
            for (TcpChannel channel : opened) {
                channel.close(e);
            }
            throw new ConnectTransportException(node, "failed to open connections", unwrap(e));
        }
        NodeChannels connection = new NodeChannels(node, channelsByType, transport);
        try {
            HandshakeResponse response = handshakeSync(connection, profile.handshakeTimeoutMillis());
            if (!TransportVersion.isCompatible(response.version())) {
                throw new ConnectTransportException(
                    node,
                    "handshake failed: remote transport version [" + response.version() + "] incompatible with supported range ["
                        + TransportVersion.MIN_COMPATIBLE + "-" + TransportVersion.CURRENT + "]"
                );
            }
        } catch (ConnectTransportException e) {
            connection.close();
            throw e;
        } catch (Exception e) {
            connection.close();
            throw new ConnectTransportException(node, "handshake failed", unwrap(e));
        }
        openConnections.add(connection);
        connection.addCloseListener(cause -> openConnections.remove(connection));
        return connection;
    }

    public <T extends TransportResponse> void sendRequest(
        Connection connection,
        String action,
        TransportRequest request,
        TransportRequestOptions options,
        TransportResponseHandler<T> handler
    ) {
        ensureStarted();
        long requestId = requestIdGenerator.incrementAndGet();
        ResponseContext<T> context = new ResponseContext<>(handler, connection, action, options.timeoutMillis());
        pendingRequests.put(requestId, context);
        context.timeoutFuture = scheduler.schedule(() -> onTimeout(requestId), options.timeoutMillis(), TimeUnit.MILLISECONDS);
        try {
            connection.sendRequest(requestId, action, request, options);
        } catch (Exception e) {
            pendingRequests.remove(requestId);
            context.timeoutFuture.cancel(false);
            handler.handleException(e instanceof TransportException te ? te : new TransportException("failed to send request [" + action + "]", e));
        }
    }

    public <T extends TransportResponse> void sendRequest(Connection connection, String action, TransportRequest request, TransportResponseHandler<T> handler) {
        sendRequest(connection, action, request, TransportRequestOptions.of(), handler);
    }

    @Override
    protected void doStart() {
        try {
            scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "transport-scheduler");
                t.setDaemon(true);
                return t;
            });
            transport = new TcpTransport(this::onMessage, threadPool.generic());
            transport.bind(bindAddress);
            transport.start();
            InetSocketAddress bound = transport.boundAddress();
            localNode = new DiscoveryNode(nodeId, bound.getHostString(), bound.getPort());
            registerBuiltinHandlers();
            keepAliveFuture = scheduler.scheduleWithFixedDelay(
                this::runKeepAlive,
                settings.pingIntervalMillis(),
                settings.pingIntervalMillis(),
                TimeUnit.MILLISECONDS
            );
        } catch (IOException e) {
            throw new TransportException("failed to start transport service", e);
        }
    }

    @Override
    protected void doStop() {
        if (keepAliveFuture != null) {
            keepAliveFuture.cancel(false);
        }
        for (NodeChannels connection : openConnections) {
            connection.close();
        }
        openConnections.clear();
        for (ResponseContext<?> context : pendingRequests.values()) {
            if (context.timeoutFuture != null) {
                context.timeoutFuture.cancel(false);
            }
        }
        pendingRequests.clear();
        if (transport != null) {
            transport.stop();
        }
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    @Override
    protected void doClose() {
    }

    private void ensureStarted() {
        if (!lifecycle.started()) {
            throw new IllegalStateException("transport service [" + nodeId + "] is not started");
        }
    }

    private void registerBuiltinHandlers() {
        registerRequestHandler(HANDSHAKE_ACTION_NAME, HandshakeRequest::new, (request, channel) -> {
            if (!TransportVersion.isCompatible(request.version())) {
                throw new TransportException(
                    "handshake rejected: incompatible transport version [" + request.version() + "], supported range ["
                        + TransportVersion.MIN_COMPATIBLE + "-" + TransportVersion.CURRENT + "]"
                );
            }
            channel.sendResponse(new HandshakeResponse(TransportVersion.CURRENT, localNode));
        });
        registerRequestHandler(PING_ACTION_NAME, EmptyRequest::new, (request, channel) -> {
            pingsReceived.incrementAndGet();
            channel.sendResponse(EmptyResponse.INSTANCE);
        });
    }

    private HandshakeResponse handshakeSync(NodeChannels connection, long timeoutMillis) throws Exception {
        long requestId = requestIdGenerator.incrementAndGet();
        CompletableFuture<HandshakeResponse> future = new CompletableFuture<>();
        TransportResponseHandler<HandshakeResponse> handler = new TransportResponseHandler<>() {
            @Override
            public void handleResponse(HandshakeResponse response) {
                future.complete(response);
            }

            @Override
            public void handleException(TransportException exp) {
                future.completeExceptionally(exp);
            }

            @Override
            public Writeable.Reader<HandshakeResponse> reader() {
                return HandshakeResponse::new;
            }
        };
        ResponseContext<HandshakeResponse> context = new ResponseContext<>(handler, connection, HANDSHAKE_ACTION_NAME, timeoutMillis);
        pendingRequests.put(requestId, context);
        context.timeoutFuture = scheduler.schedule(() -> onTimeout(requestId), timeoutMillis, TimeUnit.MILLISECONDS);
        connection.sendHandshake(requestId, new HandshakeRequest(TransportVersion.CURRENT));
        try {
            return future.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            pendingRequests.remove(requestId);
            context.timeoutFuture.cancel(false);
            throw new ConnectTransportException(connection.node(), "handshake timed out after [" + timeoutMillis + "ms]");
        } catch (ExecutionException e) {
            throw e.getCause() instanceof Exception ex ? ex : e;
        }
    }

    private void runKeepAlive() {
        long thresholdNanos = TimeUnit.MILLISECONDS.toNanos(settings.pingIntervalMillis());
        for (NodeChannels connection : openConnections) {
            if (!connection.isOpen()) {
                continue;
            }
            if (connection.idleNanos() < thresholdNanos) {
                continue;
            }
            sendRequest(
                connection,
                PING_ACTION_NAME,
                EmptyRequest.INSTANCE,
                TransportRequestOptions.of().withTimeout(settings.pingTimeoutMillis()).withChannelType(ConnectionProfile.ChannelType.PING),
                new TransportResponseHandler<EmptyResponse>() {
                    @Override
                    public void handleResponse(EmptyResponse response) {
                    }

                    @Override
                    public void handleException(TransportException exp) {
                        connection.close();
                    }

                    @Override
                    public Writeable.Reader<EmptyResponse> reader() {
                        return EmptyResponse::new;
                    }
                }
            );
        }
    }

    private void onTimeout(long requestId) {
        ResponseContext<?> context = pendingRequests.remove(requestId);
        if (context != null) {
            context.handler.handleException(new ReceiveTimeoutTransportException(context.action, requestId, context.timeoutMillis));
        }
    }

    private void onMessage(TcpChannel channel, long requestId, byte status, int version, String action, byte[] rawPayload) {
        byte[] payload;
        try {
            payload = TransportStatus.isCompress(status) ? Compressor.decompress(rawPayload) : rawPayload;
        } catch (IOException e) {
            if (TransportStatus.isRequest(status)) {
                sendErrorResponse(channel, requestId, action, e);
            } else {
                failPending(requestId, new TransportException("failed to decompress response for action [" + action + "]", e));
            }
            return;
        }
        if (TransportStatus.isRequest(status)) {
            handleInboundRequest(channel, requestId, status, version, action, payload);
        } else {
            handleInboundResponse(requestId, status, version, action, payload);
        }
    }

    private void failPending(long requestId, TransportException exception) {
        ResponseContext<?> context = pendingRequests.remove(requestId);
        if (context != null) {
            if (context.timeoutFuture != null) {
                context.timeoutFuture.cancel(false);
            }
            context.handler.handleException(exception);
        }
    }

    private void handleInboundRequest(TcpChannel channel, long requestId, byte status, int version, String action, byte[] payload) {
        RequestHandlerRegistry<?> registry = requestHandlers.get(action);
        if (registry == null) {
            sendErrorResponse(channel, requestId, action, new ActionNotFoundTransportException(action));
            return;
        }
        if (!TransportVersion.isCompatible(version)) {
            sendErrorResponse(
                channel,
                requestId,
                action,
                new TransportException(
                    "incompatible transport protocol version [" + version + "], supported range ["
                        + TransportVersion.MIN_COMPATIBLE + "-" + TransportVersion.CURRENT + "]"
                )
            );
            return;
        }
        invokeHandler(registry, channel, requestId, action, payload, TransportStatus.isCompress(status));
    }

    private <T extends TransportRequest> void invokeHandler(
        RequestHandlerRegistry<T> registry,
        TcpChannel channel,
        long requestId,
        String action,
        byte[] payload,
        boolean compress
    ) {
        TransportChannel transportChannel = new TransportChannelImpl(channel, requestId, action, compress);
        try {
            T request = registry.reader.read(new ByteBufferStreamInput(payload));
            registry.handler.messageReceived(request, transportChannel);
        } catch (Exception e) {
            try {
                transportChannel.sendResponse(e);
            } catch (IOException ignored) {
            }
        }
    }

    private void handleInboundResponse(long requestId, byte status, int version, String action, byte[] payload) {
        ResponseContext<?> context = pendingRequests.remove(requestId);
        if (context == null) {
            return;
        }
        if (context.timeoutFuture != null) {
            context.timeoutFuture.cancel(false);
        }
        if (TransportStatus.isError(status)) {
            try {
                ByteBufferStreamInput in = new ByteBufferStreamInput(payload);
                String className = in.readString();
                String message = in.readString();
                context.handler.handleException(new RemoteTransportException(context.action, className, message));
            } catch (IOException e) {
                context.handler.handleException(new TransportException("failed to read remote exception for action [" + context.action + "]", e));
            }
            return;
        }
        if (!TransportVersion.isCompatible(version)) {
            context.handler.handleException(new TransportException("incompatible transport protocol version [" + version + "] in response"));
            return;
        }
        deliverResponse(context, payload);
    }

    private <T extends TransportResponse> void deliverResponse(ResponseContext<T> context, byte[] payload) {
        try {
            T response = context.handler.reader().read(new ByteBufferStreamInput(payload));
            context.handler.handleResponse(response);
        } catch (Exception e) {
            context.handler.handleException(new TransportException("failed to deserialize response for action [" + context.action + "]", e));
        }
    }

    private void sendErrorResponse(TcpChannel channel, long requestId, String action, Exception exception) {
        try {
            transport.sendBytes(channel, MessageCodec.encodeError(requestId, action, exception));
        } catch (Exception ignored) {
        }
    }

    private static Throwable unwrap(Throwable t) {
        if (t instanceof ExecutionException && t.getCause() != null) {
            return t.getCause();
        }
        return t;
    }

    private static final class RequestHandlerRegistry<T extends TransportRequest> {
        final String action;
        final Writeable.Reader<T> reader;
        final TransportRequestHandler<T> handler;

        RequestHandlerRegistry(String action, Writeable.Reader<T> reader, TransportRequestHandler<T> handler) {
            this.action = action;
            this.reader = reader;
            this.handler = handler;
        }
    }

    private static final class ResponseContext<T extends TransportResponse> {
        final TransportResponseHandler<T> handler;
        final Connection connection;
        final String action;
        final long timeoutMillis;
        volatile ScheduledFuture<?> timeoutFuture;

        ResponseContext(TransportResponseHandler<T> handler, Connection connection, String action, long timeoutMillis) {
            this.handler = handler;
            this.connection = connection;
            this.action = action;
            this.timeoutMillis = timeoutMillis;
        }
    }

    private final class TransportChannelImpl implements TransportChannel {
        private final TcpChannel channel;
        private final long requestId;
        private final String action;
        private final boolean compress;

        TransportChannelImpl(TcpChannel channel, long requestId, String action, boolean compress) {
            this.channel = channel;
            this.requestId = requestId;
            this.action = action;
            this.compress = compress;
        }

        @Override
        public void sendResponse(TransportResponse response) throws IOException {
            transport.sendBytes(channel, MessageCodec.encodeResponse(requestId, action, response, compress));
        }

        @Override
        public void sendResponse(Exception exception) throws IOException {
            transport.sendBytes(channel, MessageCodec.encodeError(requestId, action, exception));
        }

        @Override
        public String action() {
            return action;
        }
    }
}
