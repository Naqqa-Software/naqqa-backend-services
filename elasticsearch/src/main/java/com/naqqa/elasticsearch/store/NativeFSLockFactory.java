package com.naqqa.elasticsearch.store;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public final class NativeFSLockFactory extends LockFactory {

    public static final NativeFSLockFactory INSTANCE = new NativeFSLockFactory();

    private static final Set<String> LOCK_HELD = Collections.synchronizedSet(new HashSet<>());

    private NativeFSLockFactory() {
    }

    @Override
    public Lock obtainLock(Directory dir, String lockName) throws IOException {
        if (!(dir instanceof FSDirectory fsDir)) {
            throw new IllegalArgumentException("NativeFSLockFactory requires an FSDirectory, got " + dir);
        }
        Path lockDir = fsDir.getDirectory();
        Files.createDirectories(lockDir);
        Path lockFile = lockDir.resolve(lockName);
        IOException creationException = null;
        try {
            Files.createFile(lockFile);
        } catch (IOException ignore) {
            creationException = ignore;
        }
        Path realPath;
        try {
            realPath = lockFile.toRealPath();
        } catch (IOException e) {
            if (creationException != null) {
                e.addSuppressed(creationException);
            }
            throw e;
        }
        FileTime creationTime = Files.readAttributes(realPath, BasicFileAttributes.class).creationTime();
        String key = realPath.toString();
        if (!LOCK_HELD.add(key)) {
            throw new LockObtainFailedException("Lock held by this virtual machine: " + realPath);
        }
        FileChannel channel = null;
        FileLock lock = null;
        try {
            channel = FileChannel.open(realPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            try {
                lock = channel.tryLock();
            } catch (OverlappingFileLockException e) {
                lock = null;
            }
            if (lock == null) {
                throw new LockObtainFailedException("Lock held by another program: " + realPath);
            }
            return new NativeFSLock(lock, channel, realPath, creationTime);
        } finally {
            if (lock == null) {
                if (channel != null) {
                    try {
                        channel.close();
                    } catch (IOException ignored) {
                    }
                }
                LOCK_HELD.remove(key);
            }
        }
    }

    private static final class NativeFSLock extends Lock {
        private final FileLock lock;
        private final FileChannel channel;
        private final Path path;
        private final FileTime creationTime;
        private volatile boolean closed;

        NativeFSLock(FileLock lock, FileChannel channel, Path path, FileTime creationTime) {
            this.lock = lock;
            this.channel = channel;
            this.path = path;
            this.creationTime = creationTime;
        }

        @Override
        public void ensureValid() throws IOException {
            if (closed) {
                throw new AlreadyClosedException("Lock instance already released: " + this);
            }
            if (!LOCK_HELD.contains(path.toString())) {
                throw new AlreadyClosedException("Lock path unexpectedly cleared from map: " + this);
            }
            if (!lock.isValid()) {
                throw new AlreadyClosedException("FileLock invalidated by an external force: " + this);
            }
            long size = channel.size();
            if (size != 0) {
                throw new AlreadyClosedException("Unexpected lock file size: " + size + ", (lock=" + this + ")");
            }
            FileTime ctime = Files.readAttributes(path, BasicFileAttributes.class).creationTime();
            if (!creationTime.equals(ctime)) {
                throw new AlreadyClosedException("Underlying file changed by an external force at " + ctime + ", (lock=" + this + ")");
            }
        }

        @Override
        public synchronized void close() throws IOException {
            if (closed) {
                return;
            }
            try (FileChannel ch = channel; FileLock l = lock) {
                closed = true;
            } finally {
                LOCK_HELD.remove(path.toString());
            }
        }

        @Override
        public String toString() {
            return "NativeFSLock(path=" + path + ",impl=" + lock + ",creationTime=" + creationTime + ")";
        }
    }
}
