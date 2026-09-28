package com.naqqa.elasticsearch.transport;

import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

final class NodeChannels implements Connection {

    private final DiscoveryNode node;
    private final Map<ConnectionProfile.ChannelType, TcpChannel[]> channelsByType;
    private final Map<ConnectionProfile.ChannelType, AtomicInteger> roundRobin = new EnumMap<>(ConnectionProfile.ChannelType.class);
    private final TcpTransport transport;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final List<Consumer<Exception>> closeListeners = new CopyOnWriteArrayList<>();

    NodeChannels(DiscoveryNode node, Map<ConnectionProfile.ChannelType, TcpChannel[]> channelsByType, TcpTransport transport) {
        this.node = node;
        this.channelsByType = channelsByType;
        this.transport = transport;
        for (ConnectionProfile.ChannelType type : channelsByType.keySet()) {
            roundRobin.put(type, new AtomicInteger());
        }
        for (TcpChannel[] channels : channelsByType.values()) {
            for (TcpChannel channel : channels) {
                channel.addCloseListener(cause -> close());
            }
        }
    }

    @Override
    public DiscoveryNode node() {
        return node;
    }

    Map<ConnectionProfile.ChannelType, Integer> channelCounts() {
        Map<ConnectionProfile.ChannelType, Integer> counts = new EnumMap<>(ConnectionProfile.ChannelType.class);
        for (Map.Entry<ConnectionProfile.ChannelType, TcpChannel[]> entry : channelsByType.entrySet()) {
            counts.put(entry.getKey(), entry.getValue().length);
        }
        return counts;
    }

    List<TcpChannel> allChannels() {
        List<TcpChannel> all = new ArrayList<>();
        for (TcpChannel[] channels : channelsByType.values()) {
            for (TcpChannel channel : channels) {
                all.add(channel);
            }
        }
        return all;
    }

    long idleNanos() {
        long min = Long.MAX_VALUE;
        for (TcpChannel channel : allChannels()) {
            min = Math.min(min, channel.idleNanos());
        }
        return min == Long.MAX_VALUE ? 0 : min;
    }

    TcpChannel channel(ConnectionProfile.ChannelType type) {
        TcpChannel[] channels = channelsByType.get(type);
        if (channels == null || channels.length == 0) {
            channels = channelsByType.get(ConnectionProfile.ChannelType.REG);
        }
        if (channels == null || channels.length == 0) {
            List<TcpChannel> any = allChannels();
            if (any.isEmpty()) {
                throw new ConnectTransportException(node, "no channels available on connection");
            }
            channels = any.toArray(new TcpChannel[0]);
        }
        AtomicInteger counter = roundRobin.computeIfAbsent(type, t -> new AtomicInteger());
        int idx = Math.floorMod(counter.getAndIncrement(), channels.length);
        return channels[idx];
    }

    void sendRaw(TcpChannel channel, byte[] frame) {
        transport.sendBytes(channel, frame);
    }

    void sendHandshake(long requestId, TransportRequest request) throws IOException {
        TcpChannel channel = channel(ConnectionProfile.ChannelType.REG);
        byte[] frame = MessageCodec.encodeRequest(requestId, TransportService.HANDSHAKE_ACTION_NAME, request, false, TransportStatus.HANDSHAKE);
        sendRaw(channel, frame);
    }

    @Override
    public void sendRequest(long requestId, String action, TransportRequest request, TransportRequestOptions options) throws IOException {
        if (!isOpen()) {
            throw new ConnectTransportException(node, "connection is closed");
        }
        TcpChannel channel = channel(options.channelType());
        byte[] frame = MessageCodec.encodeRequest(requestId, action, request, options.compress(), (byte) 0);
        sendRaw(channel, frame);
    }

    @Override
    public boolean isOpen() {
        return !closed.get();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        for (TcpChannel channel : allChannels()) {
            channel.close(null);
        }
        for (Consumer<Exception> listener : closeListeners) {
            listener.accept(null);
        }
    }

    @Override
    public void addCloseListener(Consumer<Exception> listener) {
        closeListeners.add(listener);
    }
}
