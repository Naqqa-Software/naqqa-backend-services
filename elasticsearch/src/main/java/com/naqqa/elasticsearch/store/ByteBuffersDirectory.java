package com.naqqa.elasticsearch.store;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.NoSuchFileException;
import java.util.Arrays;
import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.CRC32C;

public final class ByteBuffersDirectory extends BaseDirectory {

    private final ConcurrentHashMap<String, FileEntry> files = new ConcurrentHashMap<>();
    private final AtomicLong tempFileCounter = new AtomicLong();

    public ByteBuffersDirectory() {
        this(new SingleInstanceLockFactory());
    }

    public ByteBuffersDirectory(LockFactory lockFactory) {
        super(lockFactory);
    }

    private static final class FileEntry {
        final String name;
        volatile byte[] content = new byte[0];

        FileEntry(String name) {
            this.name = name;
        }
    }

    @Override
    public String[] listAll() throws IOException {
        ensureOpen();
        String[] names = files.keySet().toArray(new String[0]);
        Arrays.sort(names);
        return names;
    }

    @Override
    public void deleteFile(String name) throws IOException {
        ensureOpen();
        if (files.remove(name) == null) {
            throw new NoSuchFileException(name);
        }
    }

    @Override
    public long fileLength(String name) throws IOException {
        ensureOpen();
        FileEntry entry = files.get(name);
        if (entry == null) {
            throw new NoSuchFileException(name);
        }
        return entry.content.length;
    }

    @Override
    public boolean fileExists(String name) {
        ensureOpen();
        return files.containsKey(name);
    }

    @Override
    public IndexOutput createOutput(String name, IOContext context) throws IOException {
        ensureOpen();
        FileEntry entry = new FileEntry(name);
        if (files.putIfAbsent(name, entry) != null) {
            throw new FileAlreadyExistsException("File already exists: " + name);
        }
        return new ByteBuffersIndexOutput(entry);
    }

    @Override
    public IndexOutput createTempOutput(String prefix, String suffix, IOContext context) throws IOException {
        ensureOpen();
        while (true) {
            String name = getTempFileName(prefix, suffix, tempFileCounter.getAndIncrement());
            FileEntry entry = new FileEntry(name);
            if (files.putIfAbsent(name, entry) == null) {
                return new ByteBuffersIndexOutput(entry);
            }
        }
    }

    @Override
    public void sync(Collection<String> names) {
        ensureOpen();
    }

    @Override
    public void syncMetaData() {
        ensureOpen();
    }

    @Override
    public synchronized void rename(String source, String dest) throws IOException {
        ensureOpen();
        FileEntry entry = files.get(source);
        if (entry == null) {
            throw new NoSuchFileException(source);
        }
        FileEntry renamed = new FileEntry(dest);
        renamed.content = entry.content;
        files.put(dest, renamed);
        files.remove(source);
    }

    @Override
    public IndexInput openInput(String name, IOContext context) throws IOException {
        ensureOpen();
        FileEntry entry = files.get(name);
        if (entry == null) {
            throw new NoSuchFileException(name);
        }
        return ByteBufferIndexInput.wrap("ByteBuffersIndexInput(name=" + name + ")", entry.content);
    }

    public byte[] getFileContent(String name) throws IOException {
        FileEntry entry = files.get(name);
        if (entry == null) {
            throw new NoSuchFileException(name);
        }
        return entry.content.clone();
    }

    public void setFileContent(String name, byte[] content) throws IOException {
        FileEntry entry = files.get(name);
        if (entry == null) {
            throw new NoSuchFileException(name);
        }
        entry.content = content.clone();
    }

    @Override
    public void close() {
        isOpen = false;
        files.clear();
    }

    private static final class ByteBuffersIndexOutput extends IndexOutput {
        private final FileEntry entry;
        private final CRC32C crc = new CRC32C();
        private byte[] bytes = new byte[256];
        private int size;
        private int checksummed;
        private boolean closed;

        ByteBuffersIndexOutput(FileEntry entry) {
            super("ByteBuffersIndexOutput(name=" + entry.name + ")", entry.name);
            this.entry = entry;
        }

        private void ensureCapacity(int extra) {
            if (size + extra > bytes.length) {
                bytes = Arrays.copyOf(bytes, Math.max(size + extra, bytes.length << 1));
            }
        }

        @Override
        public void writeByte(byte b) {
            ensureCapacity(1);
            bytes[size++] = b;
        }

        @Override
        public void writeBytes(byte[] b, int offset, int length) {
            ensureCapacity(length);
            System.arraycopy(b, offset, bytes, size, length);
            size += length;
        }

        @Override
        public void writeInt(int i) {
            ensureCapacity(4);
            BitIO.putInt(bytes, size, i);
            size += 4;
        }

        @Override
        public void writeLong(long l) {
            ensureCapacity(8);
            BitIO.putLong(bytes, size, l);
            size += 8;
        }

        @Override
        public long getFilePointer() {
            return size;
        }

        @Override
        public long getChecksum() {
            if (checksummed < size) {
                crc.update(bytes, checksummed, size - checksummed);
                checksummed = size;
            }
            return crc.getValue();
        }

        @Override
        public void close() {
            if (!closed) {
                closed = true;
                entry.content = Arrays.copyOf(bytes, size);
                bytes = null;
            }
        }
    }
}
