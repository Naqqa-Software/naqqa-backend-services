package com.naqqa.elasticsearch.transport;

public final class TransportRequestOptions {

    public static final long DEFAULT_TIMEOUT_MILLIS = 30_000L;

    private final long timeoutMillis;
    private final boolean compress;
    private final ConnectionProfile.ChannelType channelType;

    private TransportRequestOptions(long timeoutMillis, boolean compress, ConnectionProfile.ChannelType channelType) {
        this.timeoutMillis = timeoutMillis;
        this.compress = compress;
        this.channelType = channelType;
    }

    public static TransportRequestOptions of() {
        return new TransportRequestOptions(DEFAULT_TIMEOUT_MILLIS, false, ConnectionProfile.ChannelType.REG);
    }

    public TransportRequestOptions withTimeout(long millis) {
        return new TransportRequestOptions(millis, compress, channelType);
    }

    public TransportRequestOptions withCompress(boolean value) {
        return new TransportRequestOptions(timeoutMillis, value, channelType);
    }

    public TransportRequestOptions withChannelType(ConnectionProfile.ChannelType type) {
        return new TransportRequestOptions(timeoutMillis, compress, type);
    }

    public long timeoutMillis() {
        return timeoutMillis;
    }

    public boolean compress() {
        return compress;
    }

    public ConnectionProfile.ChannelType channelType() {
        return channelType;
    }
}
