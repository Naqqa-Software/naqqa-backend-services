package com.naqqa.elasticsearch.store;

import java.io.IOException;

public abstract class RateLimiter {

    public abstract void setMBPerSec(double mbPerSec);

    public abstract double getMBPerSec();

    public abstract long pause(long bytes) throws IOException;

    public abstract long getMinPauseCheckBytes();
}
