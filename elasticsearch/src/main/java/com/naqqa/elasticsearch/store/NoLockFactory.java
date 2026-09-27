package com.naqqa.elasticsearch.store;

public final class NoLockFactory extends LockFactory {

    public static final NoLockFactory INSTANCE = new NoLockFactory();

    private static final Lock NO_LOCK = new Lock() {
        @Override
        public void close() {
        }

        @Override
        public void ensureValid() {
        }

        @Override
        public String toString() {
            return "NoLock";
        }
    };

    private NoLockFactory() {
    }

    @Override
    public Lock obtainLock(Directory dir, String lockName) {
        return NO_LOCK;
    }
}
