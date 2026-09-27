package com.naqqa.elasticsearch.store;

import java.io.IOException;

public final class RateLimitedIndexOutput extends IndexOutput {

    private static final int MIN_PAUSE_CHECK_BYTES = 1024;

    private final IndexOutput delegate;
    private final RateLimiter rateLimiter;
    private long bytesSinceLastPause;
    private long currentMinPauseCheckBytes;

    public RateLimitedIndexOutput(RateLimiter rateLimiter, IndexOutput delegate) {
        super("RateLimitedIndexOutput(" + delegate + ")", delegate.getName());
        this.delegate = delegate;
        this.rateLimiter = rateLimiter;
        this.currentMinPauseCheckBytes = Math.min(MIN_PAUSE_CHECK_BYTES, rateLimiter.getMinPauseCheckBytes());
    }

    private void checkRate(int bytes) throws IOException {
        bytesSinceLastPause += bytes;
        if (bytesSinceLastPause > currentMinPauseCheckBytes) {
            rateLimiter.pause(bytesSinceLastPause);
            bytesSinceLastPause = 0;
            currentMinPauseCheckBytes = Math.min(MIN_PAUSE_CHECK_BYTES, rateLimiter.getMinPauseCheckBytes());
        }
    }

    @Override
    public void writeByte(byte b) throws IOException {
        delegate.writeByte(b);
        checkRate(1);
    }

    @Override
    public void writeBytes(byte[] b, int offset, int length) throws IOException {
        int left = length;
        int off = offset;
        while (left > 0) {
            int step = Math.min(left, 8192);
            delegate.writeBytes(b, off, step);
            checkRate(step);
            off += step;
            left -= step;
        }
    }

    @Override
    public long getFilePointer() {
        return delegate.getFilePointer();
    }

    @Override
    public long getChecksum() throws IOException {
        return delegate.getChecksum();
    }

    @Override
    public void close() throws IOException {
        delegate.close();
    }
}
