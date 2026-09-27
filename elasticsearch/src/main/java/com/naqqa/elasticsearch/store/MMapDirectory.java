package com.naqqa.elasticsearch.store;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public class MMapDirectory extends FSDirectory {

    public static final int DEFAULT_MAX_CHUNK_POWER = 30;

    private static final Unmapper UNMAPPER = Unmapper.create();

    private final int chunkPower;
    private final boolean preload;

    public MMapDirectory(Path path) throws IOException {
        this(path, NativeFSLockFactory.INSTANCE, 1L << DEFAULT_MAX_CHUNK_POWER);
    }

    public MMapDirectory(Path path, long maxChunkSize) throws IOException {
        this(path, NativeFSLockFactory.INSTANCE, maxChunkSize);
    }

    public MMapDirectory(Path path, LockFactory lockFactory, long maxChunkSize) throws IOException {
        this(path, lockFactory, maxChunkSize, false);
    }

    public MMapDirectory(Path path, LockFactory lockFactory, long maxChunkSize, boolean preload) throws IOException {
        super(path, lockFactory);
        if (maxChunkSize <= 0 || Long.bitCount(maxChunkSize) != 1 || maxChunkSize > (1L << 30)) {
            throw new IllegalArgumentException("maxChunkSize must be a power of two <= 2^30, got " + maxChunkSize);
        }
        this.chunkPower = 63 - Long.numberOfLeadingZeros(maxChunkSize);
        this.preload = preload;
    }

    public static boolean isUnmapSupported() {
        return UNMAPPER.supported();
    }

    public int getMaxChunkSize() {
        return 1 << chunkPower;
    }

    @Override
    public IndexInput openInput(String name, IOContext context) throws IOException {
        ensureOpen();
        ensureCanRead(name);
        Path path = directory.resolve(name);
        String description = "MMapIndexInput(path=\"" + path + "\")";
        try (FileChannel fc = FileChannel.open(path, StandardOpenOption.READ)) {
            long length = fc.size();
            ByteBuffer[] buffers = map(fc, length);
            Runnable cleaner = () -> {
                for (ByteBuffer b : buffers) {
                    UNMAPPER.unmap(b);
                }
            };
            return ByteBufferIndexInput.wrap(description, buffers, chunkPower, length, cleaner);
        }
    }

    private ByteBuffer[] map(FileChannel fc, long length) throws IOException {
        long chunkSize = 1L << chunkPower;
        int nrBuffers = (int) Math.max(1, (length + chunkSize - 1) >>> chunkPower);
        ByteBuffer[] buffers = new ByteBuffer[nrBuffers];
        long start = 0;
        for (int i = 0; i < nrBuffers; i++) {
            long size = Math.min(chunkSize, length - start);
            MappedByteBuffer buffer = fc.map(FileChannel.MapMode.READ_ONLY, start, size);
            if (preload) {
                buffer.load();
            }
            buffers[i] = buffer;
            start += size;
        }
        return buffers;
    }

    private static final class Unmapper {
        private final Object unsafe;
        private final Method invokeCleaner;

        private Unmapper(Object unsafe, Method invokeCleaner) {
            this.unsafe = unsafe;
            this.invokeCleaner = invokeCleaner;
        }

        static Unmapper create() {
            try {
                Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
                Field field = unsafeClass.getDeclaredField("theUnsafe");
                field.setAccessible(true);
                Object unsafe = field.get(null);
                Method method = unsafeClass.getMethod("invokeCleaner", ByteBuffer.class);
                return new Unmapper(unsafe, method);
            } catch (ReflectiveOperationException | RuntimeException e) {
                return new Unmapper(null, null);
            }
        }

        boolean supported() {
            return invokeCleaner != null;
        }

        void unmap(ByteBuffer buffer) {
            if (invokeCleaner == null || !buffer.isDirect()) {
                return;
            }
            try {
                invokeCleaner.invoke(unsafe, buffer);
            } catch (ReflectiveOperationException | RuntimeException ignored) {
            }
        }
    }
}
