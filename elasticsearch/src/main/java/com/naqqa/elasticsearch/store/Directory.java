package com.naqqa.elasticsearch.store;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.util.Collection;
import java.util.Collections;
import java.util.Set;

public abstract class Directory implements Closeable {

    public abstract String[] listAll() throws IOException;

    public abstract void deleteFile(String name) throws IOException;

    public abstract long fileLength(String name) throws IOException;

    public abstract IndexOutput createOutput(String name, IOContext context) throws IOException;

    public abstract IndexOutput createTempOutput(String prefix, String suffix, IOContext context) throws IOException;

    public abstract void sync(Collection<String> names) throws IOException;

    public abstract void syncMetaData() throws IOException;

    public abstract void rename(String source, String dest) throws IOException;

    public abstract IndexInput openInput(String name, IOContext context) throws IOException;

    public ChecksumIndexInput openChecksumInput(String name, IOContext context) throws IOException {
        return new BufferedChecksumIndexInput(openInput(name, context));
    }

    public abstract Lock obtainLock(String name) throws IOException;

    @Override
    public abstract void close() throws IOException;

    public boolean fileExists(String name) throws IOException {
        for (String file : listAll()) {
            if (file.equals(name)) {
                return true;
            }
        }
        return false;
    }

    public void copyFrom(Directory from, String src, String dest, IOContext context) throws IOException {
        boolean success = false;
        try (IndexInput is = from.openInput(src, context); IndexOutput os = createOutput(dest, context)) {
            os.copyBytes(is, is.length());
            success = true;
        } finally {
            if (!success) {
                try {
                    deleteFile(dest);
                } catch (NoSuchFileException | RuntimeException ignored) {
                }
            }
        }
    }

    public Set<String> getPendingDeletions() throws IOException {
        return Collections.emptySet();
    }

    protected void ensureOpen() throws AlreadyClosedException {
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + '@' + Integer.toHexString(hashCode());
    }

    protected static String getTempFileName(String prefix, String suffix, long counter) {
        return "_" + prefix + "_" + suffix + "_" + Long.toString(counter, Character.MAX_RADIX) + ".tmp";
    }
}
