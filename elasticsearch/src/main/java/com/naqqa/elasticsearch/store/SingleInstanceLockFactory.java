package com.naqqa.elasticsearch.store;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

public final class SingleInstanceLockFactory extends LockFactory {

    private final Set<String> locks = new HashSet<>();

    @Override
    public Lock obtainLock(Directory dir, String lockName) throws IOException {
        synchronized (locks) {
            if (locks.add(lockName)) {
                return new SingleInstanceLock(lockName);
            }
            throw new LockObtainFailedException("lock instance already obtained: (dir=" + dir + ", lockName=" + lockName + ")");
        }
    }

    private final class SingleInstanceLock extends Lock {
        private final String lockName;
        private volatile boolean closed;

        SingleInstanceLock(String lockName) {
            this.lockName = lockName;
        }

        @Override
        public void ensureValid() throws IOException {
            if (closed) {
                throw new AlreadyClosedException("Lock instance already released: " + this);
            }
            synchronized (locks) {
                if (!locks.contains(lockName)) {
                    throw new AlreadyClosedException("Lock instance was invalidated from map: " + this);
                }
            }
        }

        @Override
        public synchronized void close() throws IOException {
            if (closed) {
                return;
            }
            try {
                synchronized (locks) {
                    if (!locks.remove(lockName)) {
                        throw new AlreadyClosedException("Lock was already released: " + this);
                    }
                }
            } finally {
                closed = true;
            }
        }

        @Override
        public String toString() {
            return super.toString() + ": " + lockName;
        }
    }
}
