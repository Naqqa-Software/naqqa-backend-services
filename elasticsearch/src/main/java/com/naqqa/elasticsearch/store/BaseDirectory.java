package com.naqqa.elasticsearch.store;

import java.io.IOException;

public abstract class BaseDirectory extends Directory {

    protected volatile boolean isOpen = true;
    protected final LockFactory lockFactory;

    protected BaseDirectory(LockFactory lockFactory) {
        if (lockFactory == null) {
            throw new NullPointerException("LockFactory must not be null");
        }
        this.lockFactory = lockFactory;
    }

    @Override
    public final Lock obtainLock(String name) throws IOException {
        ensureOpen();
        return lockFactory.obtainLock(this, name);
    }

    @Override
    protected final void ensureOpen() throws AlreadyClosedException {
        if (!isOpen) {
            throw new AlreadyClosedException("this Directory is closed");
        }
    }

    public LockFactory getLockFactory() {
        return lockFactory;
    }

    @Override
    public String toString() {
        return super.toString() + " lockFactory=" + lockFactory;
    }
}
