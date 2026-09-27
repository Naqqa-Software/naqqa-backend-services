package com.naqqa.elasticsearch.transport;

import java.util.EnumMap;
import java.util.Map;

public final class ConnectionProfile {

    public enum ChannelType {
        RECOVERY,
        BULK,
        REG,
        STATE,
        PING
    }

    private final Map<ChannelType, Integer> connectionsPerType;
    private final long connectTimeoutMillis;
    private final long handshakeTimeoutMillis;

    private ConnectionProfile(Map<ChannelType, Integer> connectionsPerType, long connectTimeoutMillis, long handshakeTimeoutMillis) {
        this.connectionsPerType = connectionsPerType;
        this.connectTimeoutMillis = connectTimeoutMillis;
        this.handshakeTimeoutMillis = handshakeTimeoutMillis;
    }

    public int numConnections(ChannelType type) {
        return connectionsPerType.getOrDefault(type, 0);
    }

    public Map<ChannelType, Integer> connectionsPerType() {
        return connectionsPerType;
    }

    public int totalConnections() {
        int total = 0;
        for (int count : connectionsPerType.values()) {
            total += count;
        }
        return total;
    }

    public long connectTimeoutMillis() {
        return connectTimeoutMillis;
    }

    public long handshakeTimeoutMillis() {
        return handshakeTimeoutMillis;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static ConnectionProfile buildDefault() {
        return builder()
            .addConnections(ChannelType.REG, 1)
            .addConnections(ChannelType.PING, 1)
            .addConnections(ChannelType.STATE, 1)
            .addConnections(ChannelType.BULK, 2)
            .addConnections(ChannelType.RECOVERY, 1)
            .build();
    }

    public static final class Builder {

        private final Map<ChannelType, Integer> connectionsPerType = new EnumMap<>(ChannelType.class);
        private long connectTimeoutMillis = 10_000L;
        private long handshakeTimeoutMillis = 10_000L;

        public Builder addConnections(ChannelType type, int count) {
            if (count < 0) {
                throw new IllegalArgumentException("connection count must be >= 0");
            }
            connectionsPerType.put(type, count);
            return this;
        }

        public Builder connectTimeoutMillis(long millis) {
            this.connectTimeoutMillis = millis;
            return this;
        }

        public Builder handshakeTimeoutMillis(long millis) {
            this.handshakeTimeoutMillis = millis;
            return this;
        }

        public ConnectionProfile build() {
            if (connectionsPerType.isEmpty()) {
                throw new IllegalStateException("connection profile must define at least one channel type");
            }
            return new ConnectionProfile(new EnumMap<>(connectionsPerType), connectTimeoutMillis, handshakeTimeoutMillis);
        }
    }
}
