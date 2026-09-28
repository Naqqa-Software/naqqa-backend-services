package com.naqqa.elasticsearch.store;

import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.AccessDeniedException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.CRC32C;

public class FSDirectory extends BaseDirectory {

    public static final int BUFFER_SIZE = 65536;
    private static final int CHUNK_SIZE = 8192;

    protected final Path directory;
    private final Set<String> pendingDeletes = Collections.synchronizedSet(new HashSet<>());
    private final AtomicLong nextTempFileCounter = new AtomicLong();

    public FSDirectory(Path path) throws IOException {
        this(path, NativeFSLockFactory.INSTANCE);
    }

    public FSDirectory(Path path, LockFactory lockFactory) throws IOException {
        super(lockFactory);
        if (!Files.isDirectory(path)) {
            Files.createDirectories(path);
        }
        this.directory = path.toRealPath();
    }

    public static FSDirectory open(Path path) throws IOException {
        return new MMapDirectory(path);
    }

    public Path getDirectory() {
        ensureOpen();
        return directory;
    }

    public static String[] listAll(Path dir) throws IOException {
        return listAll(dir, null);
    }

    private static String[] listAll(Path dir, Set<String> skipNames) throws IOException {
        List<String> entries = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path path : stream) {
                String name = path.getFileName().toString();
                if (skipNames == null || !skipNames.contains(name)) {
                    entries.add(name);
                }
            }
        }
        String[] array = entries.toArray(new String[0]);
        Arrays.sort(array);
        return array;
    }

    @Override
    public String[] listAll() throws IOException {
        ensureOpen();
        synchronized (pendingDeletes) {
            return listAll(directory, pendingDeletes);
        }
    }

    @Override
    public long fileLength(String name) throws IOException {
        ensureOpen();
        if (pendingDeletes.contains(name)) {
            throw new NoSuchFileException("file \"" + name + "\" is pending delete");
        }
        return Files.size(directory.resolve(name));
    }

    @Override
    public IndexOutput createOutput(String name, IOContext context) throws IOException {
        ensureOpen();
        maybeDeletePendingFiles();
        if (pendingDeletes.contains(name)) {
            privateDeleteFile(name, true);
        }
        return new FSIndexOutput(name, directory.resolve(name));
    }

    @Override
    public IndexOutput createTempOutput(String prefix, String suffix, IOContext context) throws IOException {
        ensureOpen();
        maybeDeletePendingFiles();
        while (true) {
            String name = getTempFileName(prefix, suffix, nextTempFileCounter.getAndIncrement());
            if (pendingDeletes.contains(name)) {
                continue;
            }
            try {
                return new FSIndexOutput(name, directory.resolve(name));
            } catch (FileAlreadyExistsException ignored) {
            }
        }
    }

    protected void ensureCanRead(String name) throws IOException {
        if (pendingDeletes.contains(name)) {
            throw new NoSuchFileException("file \"" + name + "\" is pending delete and cannot be opened for read");
        }
    }

    @Override
    public IndexInput openInput(String name, IOContext context) throws IOException {
        ensureOpen();
        ensureCanRead(name);
        Path path = directory.resolve(name);
        FileChannel fc = FileChannel.open(path, StandardOpenOption.READ);
        boolean success = false;
        try {
            FileChannelIndexInput input = new FileChannelIndexInput("FileChannelIndexInput(path=\"" + path + "\")", fc, 0, fc.size(), BUFFER_SIZE, false);
            success = true;
            return input;
        } finally {
            if (!success) {
                fc.close();
            }
        }
    }

    @Override
    public void sync(Collection<String> names) throws IOException {
        ensureOpen();
        for (String name : names) {
            fsync(directory.resolve(name), false);
        }
        maybeDeletePendingFiles();
    }

    @Override
    public void syncMetaData() throws IOException {
        ensureOpen();
        fsync(directory, true);
        maybeDeletePendingFiles();
    }

    @Override
    public void rename(String source, String dest) throws IOException {
        ensureOpen();
        if (pendingDeletes.contains(source)) {
            throw new NoSuchFileException("file \"" + source + "\" is pending delete and cannot be moved");
        }
        maybeDeletePendingFiles();
        if (pendingDeletes.contains(dest)) {
            privateDeleteFile(dest, true);
        }
        Files.move(directory.resolve(source), directory.resolve(dest), StandardCopyOption.ATOMIC_MOVE);
    }

    @Override
    public void deleteFile(String name) throws IOException {
        if (pendingDeletes.contains(name)) {
            throw new NoSuchFileException("file \"" + name + "\" is already pending delete");
        }
        privateDeleteFile(name, false);
        maybeDeletePendingFiles();
    }

    public void deletePendingFiles() throws IOException {
        List<String> copy;
        synchronized (pendingDeletes) {
            copy = new ArrayList<>(pendingDeletes);
        }
        for (String name : copy) {
            privateDeleteFile(name, true);
        }
    }

    private void maybeDeletePendingFiles() throws IOException {
        if (!pendingDeletes.isEmpty()) {
            deletePendingFiles();
        }
    }

    private void privateDeleteFile(String name, boolean isPendingDelete) throws IOException {
        try {
            Files.delete(directory.resolve(name));
            pendingDeletes.remove(name);
        } catch (NoSuchFileException e) {
            pendingDeletes.remove(name);
            if (!isPendingDelete) {
                throw e;
            }
        } catch (IOException e) {
            if (e instanceof AccessDeniedException || isWindows()) {
                pendingDeletes.add(name);
            } else {
                throw e;
            }
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().startsWith("windows");
    }

    @Override
    public Set<String> getPendingDeletions() throws IOException {
        deletePendingFiles();
        synchronized (pendingDeletes) {
            return Set.copyOf(pendingDeletes);
        }
    }

    @Override
    public synchronized void close() throws IOException {
        isOpen = false;
        deletePendingFiles();
    }

    public static void fsync(Path fileToSync, boolean isDir) throws IOException {
        if (isDir && isWindows()) {
            return;
        }
        IOException lastException = null;
        for (int retry = 0; retry < 5; retry++) {
            try (FileChannel file = FileChannel.open(fileToSync, isDir ? StandardOpenOption.READ : StandardOpenOption.WRITE)) {
                file.force(true);
                return;
            } catch (IOException e) {
                if (isDir) {
                    return;
                }
                lastException = e;
                try {
                    Thread.sleep(5);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
        throw lastException;
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "@" + directory + " lockFactory=" + lockFactory;
    }

    static final class FSIndexOutput extends IndexOutput {
        private final OutputStream os;
        private final CRC32C crc = new CRC32C();
        private final byte[] buffer = new byte[CHUNK_SIZE];
        private int upto;
        private long flushed;
        private boolean closed;

        FSIndexOutput(String name, Path path) throws IOException {
            super("FSIndexOutput(path=\"" + path + "\")", name);
            this.os = Files.newOutputStream(path, StandardOpenOption.WRITE, StandardOpenOption.CREATE_NEW);
        }

        private void flushBuffer() throws IOException {
            if (upto > 0) {
                crc.update(buffer, 0, upto);
                os.write(buffer, 0, upto);
                flushed += upto;
                upto = 0;
            }
        }

        @Override
        public void writeByte(byte b) throws IOException {
            if (upto == buffer.length) {
                flushBuffer();
            }
            buffer[upto++] = b;
        }

        @Override
        public void writeBytes(byte[] b, int offset, int length) throws IOException {
            if (length <= buffer.length - upto) {
                System.arraycopy(b, offset, buffer, upto, length);
                upto += length;
                return;
            }
            flushBuffer();
            if (length >= buffer.length) {
                crc.update(b, offset, length);
                os.write(b, offset, length);
                flushed += length;
            } else {
                System.arraycopy(b, offset, buffer, 0, length);
                upto = length;
            }
        }

        @Override
        public void writeInt(int i) throws IOException {
            if (buffer.length - upto < 4) {
                flushBuffer();
            }
            BitIO.putInt(buffer, upto, i);
            upto += 4;
        }

        @Override
        public void writeLong(long l) throws IOException {
            if (buffer.length - upto < 8) {
                flushBuffer();
            }
            BitIO.putLong(buffer, upto, l);
            upto += 8;
        }

        @Override
        public long getFilePointer() {
            return flushed + upto;
        }

        @Override
        public long getChecksum() throws IOException {
            flushBuffer();
            return crc.getValue();
        }

        @Override
        public void close() throws IOException {
            if (!closed) {
                closed = true;
                try {
                    flushBuffer();
                } finally {
                    os.close();
                }
            }
        }
    }

    static final class FileChannelIndexInput extends IndexInput implements RandomAccessInput {
        private final FileChannel channel;
        private final long off;
        private final long end;
        private final int bufferSize;
        private final boolean isClone;
        private ByteBuffer buffer;
        private long bufferStart;
        private int bufferLength;
        private int bufferPos;

        FileChannelIndexInput(String desc, FileChannel channel, long off, long length, int bufferSize, boolean isClone) {
            super(desc);
            this.channel = channel;
            this.off = off;
            this.end = off + length;
            this.bufferSize = bufferSize;
            this.isClone = isClone;
            this.bufferStart = 0;
            this.bufferLength = 0;
            this.bufferPos = 0;
        }

        private void refill() throws IOException {
            long start = bufferStart + bufferPos;
            long len = Math.min(bufferSize, length() - start);
            if (len <= 0) {
                throw new EOFException("read past EOF: " + this);
            }
            if (buffer == null) {
                buffer = ByteBuffer.allocate(bufferSize).order(ByteOrder.LITTLE_ENDIAN);
            }
            buffer.clear();
            buffer.limit((int) len);
            readInternal(buffer, off + start);
            bufferStart = start;
            bufferPos = 0;
            bufferLength = (int) len;
        }

        private void readInternal(ByteBuffer dst, long position) throws IOException {
            if (!channel.isOpen()) {
                throw new AlreadyClosedException("Already closed: " + this);
            }
            long p = position;
            while (dst.hasRemaining()) {
                int n = channel.read(dst, p);
                if (n < 0) {
                    throw new EOFException("read past EOF: " + this);
                }
                p += n;
            }
        }

        @Override
        public byte readByte() throws IOException {
            if (bufferPos >= bufferLength) {
                refill();
            }
            return buffer.get(bufferPos++);
        }

        @Override
        public void readBytes(byte[] b, int offset, int len) throws IOException {
            int available = bufferLength - bufferPos;
            if (len <= available) {
                if (len > 0) {
                    buffer.get(bufferPos, b, offset, len);
                    bufferPos += len;
                }
                return;
            }
            if (available > 0) {
                buffer.get(bufferPos, b, offset, available);
                offset += available;
                len -= available;
                bufferPos += available;
            }
            if (len < bufferSize) {
                refill();
                if (bufferLength < len) {
                    throw new EOFException("read past EOF: " + this);
                }
                buffer.get(0, b, offset, len);
                bufferPos = len;
            } else {
                long start = bufferStart + bufferPos;
                if (start + len > length()) {
                    throw new EOFException("read past EOF: " + this);
                }
                readInternal(ByteBuffer.wrap(b, offset, len), off + start);
                bufferStart = start + len;
                bufferPos = 0;
                bufferLength = 0;
            }
        }

        @Override
        public short readShort() throws IOException {
            if (bufferLength - bufferPos >= 2) {
                short v = buffer.getShort(bufferPos);
                bufferPos += 2;
                return v;
            }
            return super.readShort();
        }

        @Override
        public int readInt() throws IOException {
            if (bufferLength - bufferPos >= 4) {
                int v = buffer.getInt(bufferPos);
                bufferPos += 4;
                return v;
            }
            return super.readInt();
        }

        @Override
        public long readLong() throws IOException {
            if (bufferLength - bufferPos >= 8) {
                long v = buffer.getLong(bufferPos);
                bufferPos += 8;
                return v;
            }
            return super.readLong();
        }

        @Override
        public long getFilePointer() {
            return bufferStart + bufferPos;
        }

        @Override
        public void seek(long pos) throws IOException {
            if (pos < 0 || pos > length()) {
                throw new EOFException("seek past EOF: pos=" + pos + ": " + this);
            }
            if (pos >= bufferStart && pos < bufferStart + bufferLength) {
                bufferPos = (int) (pos - bufferStart);
            } else {
                bufferStart = pos;
                bufferPos = 0;
                bufferLength = 0;
            }
        }

        @Override
        public long length() {
            return end - off;
        }

        @Override
        public byte readByte(long pos) throws IOException {
            if (pos >= bufferStart && pos < bufferStart + bufferLength) {
                return buffer.get((int) (pos - bufferStart));
            }
            seek(pos);
            return readByte();
        }

        @Override
        public void readBytes(long pos, byte[] bytes, int offset, int length) throws IOException {
            seek(pos);
            readBytes(bytes, offset, length);
        }

        @Override
        public short readShort(long pos) throws IOException {
            if (pos >= bufferStart && pos + 2 <= bufferStart + bufferLength) {
                return buffer.getShort((int) (pos - bufferStart));
            }
            seek(pos);
            return readShort();
        }

        @Override
        public int readInt(long pos) throws IOException {
            if (pos >= bufferStart && pos + 4 <= bufferStart + bufferLength) {
                return buffer.getInt((int) (pos - bufferStart));
            }
            seek(pos);
            return readInt();
        }

        @Override
        public long readLong(long pos) throws IOException {
            if (pos >= bufferStart && pos + 8 <= bufferStart + bufferLength) {
                return buffer.getLong((int) (pos - bufferStart));
            }
            seek(pos);
            return readLong();
        }

        @Override
        public FileChannelIndexInput clone() {
            FileChannelIndexInput clone = new FileChannelIndexInput(toString(), channel, off, end - off, bufferSize, true);
            clone.bufferStart = getFilePointer();
            return clone;
        }

        @Override
        public IndexInput slice(String sliceDescription, long offset, long length) throws IOException {
            if (offset < 0 || length < 0 || offset + length > length()) {
                throw new IllegalArgumentException("slice() " + sliceDescription + " out of bounds: offset=" + offset
                    + ",length=" + length + ",fileLength=" + length() + ": " + this);
            }
            return new FileChannelIndexInput(getFullSliceDescription(sliceDescription), channel, off + offset, length, bufferSize, true);
        }

        @Override
        public RandomAccessInput randomAccessSlice(long offset, long length) throws IOException {
            return (RandomAccessInput) slice("randomaccess", offset, length);
        }

        @Override
        public void close() throws IOException {
            if (!isClone) {
                channel.close();
            }
        }
    }
}
