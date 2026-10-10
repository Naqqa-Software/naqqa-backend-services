package com.naqqa.elasticsearch.index.translog;

import com.naqqa.elasticsearch.common.UUIDs;

import java.io.Closeable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class Translog implements Closeable {

    private static final Pattern GENERATION_PATTERN = Pattern.compile("translog-(\\d+)\\.tlog");

    private final Path location;
    private final TranslogConfig config;
    private final String translogUUID;
    private final Object generationLock = new Object();
    private final NavigableMap<Long, TranslogReader> readers = new TreeMap<>();
    private volatile TranslogWriter current;
    private final ScheduledExecutorService asyncSyncScheduler;
    private volatile boolean closed = false;
    private volatile long lastGenerationStartTime;

    private Translog(Path location, TranslogConfig config) throws IOException {
        this.location = location;
        this.config = config;
        Files.createDirectories(location);
        List<Long> generations = scanGenerations(location);
        String uuid = config.translogUUID();
        if (generations.isEmpty()) {
            if (uuid == null) {
                uuid = UUIDs.randomBase64UUID();
            }
            this.translogUUID = uuid;
            this.current = createNewGeneration(1L);
        } else {
            for (int i = 0; i < generations.size(); i++) {
                long gen = generations.get(i);
                Path file = fileForGeneration(location, gen);
                TranslogReader reader = TranslogReader.openAndRecover(file, gen);
                if (i == generations.size() - 1) {
                    this.current = reader.toWriter();
                } else {
                    readers.put(gen, reader);
                }
            }
            this.translogUUID = uuid != null ? uuid : UUIDs.randomBase64UUID();
        }
        this.lastGenerationStartTime = System.currentTimeMillis();
        if (config.durability() == Durability.ASYNC) {
            this.asyncSyncScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "translog-async-sync");
                t.setDaemon(true);
                return t;
            });
            long intervalMillis = Math.max(1L, config.syncInterval().millis());
            this.asyncSyncScheduler.scheduleWithFixedDelay(this::safeAsyncSync, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
        } else {
            this.asyncSyncScheduler = null;
        }
    }

    public static Translog open(Path path, TranslogConfig config) throws IOException {
        return new Translog(path, config);
    }

    private void safeAsyncSync() {
        try {
            sync();
        } catch (IOException ignored) {
        }
    }

    private static List<Long> scanGenerations(Path dir) throws IOException {
        List<Long> gens = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return gens;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "translog-*.tlog")) {
            for (Path p : stream) {
                Matcher m = GENERATION_PATTERN.matcher(p.getFileName().toString());
                if (m.matches()) {
                    gens.add(Long.parseLong(m.group(1)));
                }
            }
        }
        Collections.sort(gens);
        return gens;
    }

    private static Path fileForGeneration(Path dir, long generation) {
        return dir.resolve("translog-" + generation + ".tlog");
    }

    private static Path checkpointForGeneration(Path dir, long generation) {
        return dir.resolve("translog-" + generation + ".ckp");
    }

    private TranslogWriter createNewGeneration(long generation) throws IOException {
        Path file = fileForGeneration(location, generation);
        return TranslogWriter.create(file, generation, translogUUID);
    }

    public String getTranslogUUID() {
        return translogUUID;
    }

    public Location add(Operation op) throws IOException {
        return add(op, true);
    }

    public Location add(Operation op, boolean fsync) throws IOException {
        ensureOpen();
        Location loc;
        synchronized (generationLock) {
            loc = current.add(op);
            if (fsync && config.durability() == Durability.REQUEST) {
                current.sync();
            }
            maybeRoll();
        }
        return loc;
    }

    public void sync() throws IOException {
        synchronized (generationLock) {
            if (!closed) {
                current.sync();
            }
        }
    }

    public boolean isSyncNeeded() {
        synchronized (generationLock) {
            return current.syncNeeded();
        }
    }

    public long totalFsyncs() {
        synchronized (generationLock) {
            return current.fsyncCount();
        }
    }

    public long currentFileGeneration() {
        synchronized (generationLock) {
            return current.generation();
        }
    }

    public long sizeInBytes() {
        return current.sizeInBytes();
    }

    /**
     * Size of every translog generation still on disk (frozen readers plus the current writer). This is the amount
     * of data that would have to be replayed after a crash, so flush decisions must be based on it rather than on
     * the size of the current generation alone (which is rolled long before a large flush threshold is reached).
     */
    public long totalSizeInBytes() {
        synchronized (generationLock) {
            long total = current.sizeInBytes();
            for (TranslogReader r : readers.values()) {
                total += r.length();
            }
            return total;
        }
    }

    public int getMinFileGeneration() {
        synchronized (generationLock) {
            if (readers.isEmpty()) {
                return (int) current.generation();
            }
            return (int) readers.firstKey().longValue();
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("translog is already closed");
        }
    }

    private void maybeRoll() throws IOException {
        long thresholdBytes = config.generationThresholdSize().getBytes();
        boolean sizeExceeded = thresholdBytes >= 0 && current.sizeInBytes() >= thresholdBytes;
        boolean ageExceeded = config.maxGenerationAge() != null
            && config.maxGenerationAge().millis() > 0
            && (System.currentTimeMillis() - lastGenerationStartTime) >= config.maxGenerationAge().millis();
        if (sizeExceeded || ageExceeded) {
            rollGeneration();
        }
    }

    public void rollGeneration() throws IOException {
        synchronized (generationLock) {
            ensureOpen();
            current.sync();
            long oldGen = current.generation();
            TranslogReader frozen = current.freeze();
            readers.put(oldGen, frozen);
            writeCheckpoint(frozen);
            long newGen = oldGen + 1;
            current = createNewGeneration(newGen);
            lastGenerationStartTime = System.currentTimeMillis();
        }
    }

    private void writeCheckpoint(TranslogReader r) throws IOException {
        Checkpoint checkpoint = new Checkpoint(r.generation(), r.minSeqNo(), r.maxSeqNo(), r.length(), r.opCount());
        checkpoint.write(checkpointForGeneration(location, r.generation()));
    }

    public void trimUnreferencedReaders(long minRequiredSeqNo) throws IOException {
        synchronized (generationLock) {
            List<TranslogReader> toDelete = new ArrayList<>();
            var it = readers.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<Long, TranslogReader> e = it.next();
                TranslogReader r = e.getValue();
                if (r.opCount() == 0 || r.maxSeqNo() < minRequiredSeqNo) {
                    toDelete.add(r);
                    it.remove();
                }
            }
            for (TranslogReader r : toDelete) {
                r.close();
                Files.deleteIfExists(r.path());
                Files.deleteIfExists(checkpointForGeneration(location, r.generation()));
            }
        }
    }

    public Snapshot newSnapshot() {
        return newSnapshot(0L);
    }

    public Snapshot newSnapshot(long fromSeqNo) {
        synchronized (generationLock) {
            List<BaseTranslogReader> ordered = new ArrayList<>(readers.values());
            ordered.add(current);
            List<Operation> ops = new ArrayList<>();
            for (BaseTranslogReader r : ordered) {
                try {
                    for (Operation op : r.readAllOperations()) {
                        if (op.seqNo() >= fromSeqNo) {
                            ops.add(op);
                        }
                    }
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
            ops.sort(Comparator.comparingLong(Operation::seqNo));
            return new Snapshot(ops);
        }
    }

    @Override
    public void close() throws IOException {
        synchronized (generationLock) {
            if (closed) {
                return;
            }
            closed = true;
            if (asyncSyncScheduler != null) {
                asyncSyncScheduler.shutdownNow();
            }
            current.close();
            for (TranslogReader r : readers.values()) {
                r.close();
            }
        }
    }

    public record Location(long generation, long translogOffset, int size) implements Comparable<Location> {
        @Override
        public int compareTo(Location o) {
            int c = Long.compare(generation, o.generation);
            if (c != 0) {
                return c;
            }
            return Long.compare(translogOffset, o.translogOffset);
        }
    }

    public static final class Snapshot implements Closeable {
        private final List<Operation> ops;
        private int index;

        private Snapshot(List<Operation> ops) {
            this.ops = ops;
        }

        public int totalOperations() {
            return ops.size();
        }

        public Operation next() {
            if (index >= ops.size()) {
                return null;
            }
            return ops.get(index++);
        }

        @Override
        public void close() {
        }
    }
}
