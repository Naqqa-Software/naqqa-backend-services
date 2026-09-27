package com.naqqa.elasticsearch.transport;

import com.naqqa.elasticsearch.common.settings.Settings;

public final class TransportSettings {

    public static final long DEFAULT_CONNECT_TIMEOUT_MILLIS = 10_000L;
    public static final long DEFAULT_HANDSHAKE_TIMEOUT_MILLIS = 10_000L;
    public static final long DEFAULT_PING_INTERVAL_MILLIS = 30_000L;
    public static final long DEFAULT_PING_TIMEOUT_MILLIS = 30_000L;

    private final long connectTimeoutMillis;
    private final long handshakeTimeoutMillis;
    private final long pingIntervalMillis;
    private final long pingTimeoutMillis;

    public TransportSettings(long connectTimeoutMillis, long handshakeTimeoutMillis, long pingIntervalMillis, long pingTimeoutMillis) {
        this.connectTimeoutMillis = connectTimeoutMillis;
        this.handshakeTimeoutMillis = handshakeTimeoutMillis;
        this.pingIntervalMillis = pingIntervalMillis;
        this.pingTimeoutMillis = pingTimeoutMillis;
    }

    public static TransportSettings defaults() {
        return new TransportSettings(
            DEFAULT_CONNECT_TIMEOUT_MILLIS,
            DEFAULT_HANDSHAKE_TIMEOUT_MILLIS,
            DEFAULT_PING_INTERVAL_MILLIS,
            DEFAULT_PING_TIMEOUT_MILLIS
        );
    }

    public static TransportSettings fromSettings(Settings settings) {
        return new TransportSettings(
            settings.getAsLong("transport.connect_timeout_millis", DEFAULT_CONNECT_TIMEOUT_MILLIS),
            settings.getAsLong("transport.handshake_timeout_millis", DEFAULT_HANDSHAKE_TIMEOUT_MILLIS),
            settings.getAsLong("transport.ping_interval_millis", DEFAULT_PING_INTERVAL_MILLIS),
            settings.getAsLong("transport.ping_timeout_millis", DEFAULT_PING_TIMEOUT_MILLIS)
        );
    }

    public long connectTimeoutMillis() {
        return connectTimeoutMillis;
    }

    public long handshakeTimeoutMillis() {
        return handshakeTimeoutMillis;
    }

    public long pingIntervalMillis() {
        return pingIntervalMillis;
    }

    public long pingTimeoutMillis() {
        return pingTimeoutMillis;
    }
}
