package com.naqqa.elasticsearch.store;

import java.io.IOException;

public abstract class ChecksumIndexInput extends IndexInput {

    protected ChecksumIndexInput(String resourceDescription) {
        super(resourceDescription);
    }

    public abstract long getChecksum() throws IOException;

    @Override
    public void seek(long pos) throws IOException {
        long curFP = getFilePointer();
        long skip = pos - curFP;
        if (skip < 0) {
            throw new IllegalStateException(getClass() + " cannot seek backwards (pos=" + pos + " getFilePointer()=" + curFP + ")");
        }
        skipByReading(skip);
    }

    protected abstract void skipByReading(long numBytes) throws IOException;
}
