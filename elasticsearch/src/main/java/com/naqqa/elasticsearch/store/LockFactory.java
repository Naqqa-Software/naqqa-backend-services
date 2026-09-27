package com.naqqa.elasticsearch.store;

import java.io.IOException;

public abstract class LockFactory {

    public abstract Lock obtainLock(Directory dir, String lockName) throws IOException;
}
