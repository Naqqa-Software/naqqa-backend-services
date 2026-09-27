package com.naqqa.elasticsearch.index.recovery;

import com.naqqa.elasticsearch.index.engine.GetResult;
import com.naqqa.elasticsearch.index.mapper.MapperService;
import com.naqqa.elasticsearch.index.shard.IndexShard;
import com.naqqa.elasticsearch.test.Test;

import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;

import static com.naqqa.elasticsearch.test.Assert.assertNotNull;
import static com.naqqa.elasticsearch.test.Assert.assertThrows;
import static com.naqqa.elasticsearch.test.Assert.assertTrue;

public final class StoreRecoveryTest {

    @Test
    public void recoverFromLocalStoreReplaysPendingTranslogOps() throws Exception {
        Path shardPath = RecoveryTestSupport.newTempShardPath("store-recovery");
        MapperService mapperService = RecoveryTestSupport.newMapperService();
        IndexShard shard = RecoveryTestSupport.openShard(shardPath, mapperService);
        shard.index("doc-1", Map.of("title", "unflushed"));
        shard.index("doc-2", Map.of("title", "also-unflushed"));
        shard.close();

        IndexShard reopened = StoreRecovery.recoverFromLocalStore(shardPath, RecoveryTestSupport.newMapperService());
        try {
            GetResult r1 = reopened.get("doc-1");
            GetResult r2 = reopened.get("doc-2");
            assertTrue(r1.exists(), "expected doc-1 to survive crash recovery from translog");
            assertTrue(r2.exists(), "expected doc-2 to survive crash recovery from translog");
        } finally {
            reopened.close();
        }
    }

    @Test
    public void corruptedSegmentFileCausesCorruptShardException() throws Exception {
        Path shardPath = RecoveryTestSupport.newTempShardPath("store-corrupt");
        MapperService mapperService = RecoveryTestSupport.newMapperService();
        IndexShard shard = RecoveryTestSupport.openShard(shardPath, mapperService);
        shard.index("doc-1", Map.of("title", "hello"));
        shard.flush(true);
        shard.close();

        Path indexDir = shardPath.resolve("index");
        Path victim = null;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(indexDir)) {
            for (Path p : stream) {
                if (p.getFileName().toString().endsWith(".fld")) {
                    victim = p;
                    break;
                }
            }
        }
        assertNotNull(victim);
        try (FileChannel channel = FileChannel.open(victim, StandardOpenOption.WRITE)) {
            long size = channel.size();
            long pos = Math.max(0, size / 2);
            channel.write(ByteBuffer.wrap(new byte[]{(byte) 0xFF, (byte) 0x00, (byte) 0xAB}), pos);
        }

        assertThrows(CorruptShardException.class,
            () -> StoreRecovery.recoverFromLocalStore(shardPath, RecoveryTestSupport.newMapperService()));
    }
}
