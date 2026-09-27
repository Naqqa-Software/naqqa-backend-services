package com.naqqa.elasticsearch.index.translog;

import com.naqqa.elasticsearch.common.bytes.BytesReference;
import com.naqqa.elasticsearch.common.unit.ByteSizeValue;
import com.naqqa.elasticsearch.common.unit.TimeValue;
import com.naqqa.elasticsearch.test.Assert;
import com.naqqa.elasticsearch.test.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

public final class TranslogTest {

    private Path newTempDir() throws IOException {
        return Files.createTempDirectory("translog-test");
    }

    private Operation.Index index(String id, long seqNo) {
        return new Operation.Index(id, seqNo, 1L, seqNo + 1, BytesReference.of(("src-" + id).getBytes()), null);
    }

    @Test
    public void appendAndReadBackOrdering() throws IOException {
        Path dir = newTempDir();
        Translog translog = Translog.open(dir, TranslogConfig.defaultConfig(dir));
        try {
            for (int i = 0; i < 5; i++) {
                translog.add(index("id" + i, i));
            }
            Translog.Snapshot snapshot = translog.newSnapshot();
            Assert.assertEquals(5, snapshot.totalOperations());
            long expected = 0;
            Operation op;
            while ((op = snapshot.next()) != null) {
                Assert.assertEquals(expected, op.seqNo());
                Assert.assertEquals("id" + expected, ((Operation.Index) op).id());
                expected++;
            }
            Assert.assertEquals(5L, expected);
        } finally {
            translog.close();
        }
    }

    @Test
    public void corruptedTailIsTruncatedAndPriorOpsSurvive() throws IOException {
        Path dir = newTempDir();
        Translog translog = Translog.open(dir, TranslogConfig.defaultConfig(dir));
        long goodLength;
        try {
            translog.add(index("a", 0));
            translog.add(index("b", 1));
            translog.add(index("c", 2));
        } finally {
            translog.close();
        }
        Path file = dir.resolve("translog-1.tlog");
        goodLength = Files.size(file);
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
            channel.write(ByteBuffer.wrap(new byte[] { 1, 2, 3 }), goodLength);
        }
        Assert.assertEquals(goodLength + 3, Files.size(file));

        Translog reopened = Translog.open(dir, TranslogConfig.defaultConfig(dir));
        try {
            Translog.Snapshot snapshot = reopened.newSnapshot();
            Assert.assertEquals(3, snapshot.totalOperations());
            Assert.assertEquals(goodLength, Files.size(file));
        } finally {
            reopened.close();
        }
    }

    @Test
    public void badChecksumDropsLastRecordOnly() throws IOException {
        Path dir = newTempDir();
        Translog translog = Translog.open(dir, TranslogConfig.defaultConfig(dir));
        Translog.Location lastLocation;
        try {
            translog.add(index("a", 0));
            translog.add(index("b", 1));
            lastLocation = translog.add(index("c", 2));
        } finally {
            translog.close();
        }
        Path file = dir.resolve("translog-1.tlog");
        long corruptPos = lastLocation.translogOffset() + 5;
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE, StandardOpenOption.READ)) {
            ByteBuffer buf = ByteBuffer.allocate(1);
            channel.read(buf, corruptPos);
            buf.flip();
            byte b = buf.get(0);
            ByteBuffer out = ByteBuffer.wrap(new byte[] { (byte) (b ^ 0xFF) });
            channel.write(out, corruptPos);
        }

        Translog reopened = Translog.open(dir, TranslogConfig.defaultConfig(dir));
        try {
            Translog.Snapshot snapshot = reopened.newSnapshot();
            Assert.assertEquals(2, snapshot.totalOperations());
            Operation op1 = snapshot.next();
            Operation op2 = snapshot.next();
            Assert.assertEquals(0L, op1.seqNo());
            Assert.assertEquals(1L, op2.seqNo());
        } finally {
            reopened.close();
        }
    }

    @Test
    public void generationRolloverAndTrimRemovesOnlyPersistedGenerations() throws IOException {
        Path dir = newTempDir();
        TranslogConfig config = TranslogConfig.defaultConfig(dir).withGenerationThresholdSize(ByteSizeValue.ofBytes(1));
        Translog translog = Translog.open(dir, config);
        try {
            for (int i = 0; i < 5; i++) {
                translog.add(index("id" + i, i));
            }
            Assert.assertEquals(6L, translog.currentFileGeneration());
            Assert.assertEquals(1, translog.getMinFileGeneration());

            translog.trimUnreferencedReaders(3L);
            Assert.assertEquals(4, translog.getMinFileGeneration());
            Assert.assertFalse(Files.exists(dir.resolve("translog-1.tlog")));
            Assert.assertFalse(Files.exists(dir.resolve("translog-2.tlog")));
            Assert.assertFalse(Files.exists(dir.resolve("translog-3.tlog")));
            Assert.assertTrue(Files.exists(dir.resolve("translog-4.tlog")));
            Assert.assertTrue(Files.exists(dir.resolve("translog-5.tlog")));

            Translog.Snapshot snapshot = translog.newSnapshot();
            List<Long> remainingSeqNos = new ArrayList<>();
            Operation op;
            while ((op = snapshot.next()) != null) {
                remainingSeqNos.add(op.seqNo());
            }
            Assert.assertEquals(List.of(3L, 4L), remainingSeqNos);
        } finally {
            translog.close();
        }
    }

    @Test
    public void requestDurabilityFsyncsSynchronously() throws IOException {
        Path dir = newTempDir();
        TranslogConfig config = TranslogConfig.defaultConfig(dir).withDurability(Durability.REQUEST);
        Translog translog = Translog.open(dir, config);
        try {
            Assert.assertEquals(0L, translog.totalFsyncs());
            translog.add(index("a", 0));
            Assert.assertEquals(1L, translog.totalFsyncs());
            translog.add(index("b", 1));
            Assert.assertEquals(2L, translog.totalFsyncs());
        } finally {
            translog.close();
        }
    }

    @Test
    public void asyncDurabilityBatchesAndSyncsOnDemand() throws IOException {
        Path dir = newTempDir();
        TranslogConfig config = TranslogConfig.defaultConfig(dir)
            .withDurability(Durability.ASYNC)
            .withSyncInterval(TimeValue.timeValueMillis(50));
        Translog translog = Translog.open(dir, config);
        try {
            translog.add(index("a", 0));
            translog.add(index("b", 1));
            Assert.assertEquals(0L, translog.totalFsyncs());
            translog.sync();
            Assert.assertEquals(1L, translog.totalFsyncs());
            Assert.assertFalse(translog.isSyncNeeded());
        } finally {
            translog.close();
        }
    }

    @Test
    public void asyncDurabilityEventuallySyncsViaScheduler() throws IOException, InterruptedException {
        Path dir = newTempDir();
        TranslogConfig config = TranslogConfig.defaultConfig(dir)
            .withDurability(Durability.ASYNC)
            .withSyncInterval(TimeValue.timeValueMillis(20));
        Translog translog = Translog.open(dir, config);
        try {
            translog.add(index("a", 0));
            long deadline = System.currentTimeMillis() + 3000;
            while (translog.totalFsyncs() == 0 && System.currentTimeMillis() < deadline) {
                Thread.sleep(20);
            }
            Assert.assertTrue(translog.totalFsyncs() > 0);
        } finally {
            translog.close();
        }
    }

    @Test
    public void closeAndReopenReplaysFromGivenSeqNo() throws IOException {
        Path dir = newTempDir();
        Translog translog = Translog.open(dir, TranslogConfig.defaultConfig(dir));
        try {
            for (int i = 0; i < 5; i++) {
                translog.add(index("id" + i, i));
            }
        } finally {
            translog.close();
        }
        Translog reopened = Translog.open(dir, TranslogConfig.defaultConfig(dir));
        try {
            Translog.Snapshot snapshot = reopened.newSnapshot(3L);
            List<Long> seqNos = new ArrayList<>();
            Operation op;
            while ((op = snapshot.next()) != null) {
                seqNos.add(op.seqNo());
            }
            Assert.assertEquals(List.of(3L, 4L), seqNos);
        } finally {
            reopened.close();
        }
    }

    @Test
    public void deleteAndNoOpOperationsRoundTrip() throws IOException {
        Path dir = newTempDir();
        Translog translog = Translog.open(dir, TranslogConfig.defaultConfig(dir));
        try {
            translog.add(new Operation.Delete("docA", 0L, 1L, 5L));
            translog.add(new Operation.NoOp(1L, 1L, "primary failover"));
        } finally {
            translog.close();
        }
        Translog reopened = Translog.open(dir, TranslogConfig.defaultConfig(dir));
        try {
            Translog.Snapshot snapshot = reopened.newSnapshot();
            Operation.Delete del = (Operation.Delete) snapshot.next();
            Operation.NoOp noOp = (Operation.NoOp) snapshot.next();
            Assert.assertEquals("docA", del.id());
            Assert.assertEquals(5L, del.version());
            Assert.assertEquals("primary failover", noOp.reason());
        } finally {
            reopened.close();
        }
    }
}
