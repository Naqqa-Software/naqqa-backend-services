package com.naqqa.elasticsearch.index.recovery;

import com.naqqa.elasticsearch.index.mapper.MapperService;
import com.naqqa.elasticsearch.index.shard.IndexShard;
import com.naqqa.elasticsearch.index.translog.TranslogConfig;
import com.naqqa.elasticsearch.snapshots.model.ShardId;
import com.naqqa.elasticsearch.snapshots.repository.Repository;
import com.naqqa.elasticsearch.snapshots.restore.RestoreRequest;
import com.naqqa.elasticsearch.snapshots.source.ShardRestoreTarget;
import com.naqqa.elasticsearch.codec.Codec;
import com.naqqa.elasticsearch.store.CodecUtil;
import com.naqqa.elasticsearch.store.Directory;
import com.naqqa.elasticsearch.store.FSDirectory;
import com.naqqa.elasticsearch.store.IOContext;
import com.naqqa.elasticsearch.store.IndexInput;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class StoreRecovery {

    private StoreRecovery() {
    }

    public static IndexShard recoverFromLocalStore(Path shardPath, MapperService mapperService) throws IOException {
        return recoverFromLocalStore(shardPath, mapperService, TranslogConfig.defaultConfig(shardPath.resolve("translog")));
    }

    public static IndexShard recoverFromLocalStore(Path shardPath, MapperService mapperService, TranslogConfig translogConfig) throws IOException {
        Path indexPath = shardPath.resolve("index");
        try (Directory directory = new FSDirectory(indexPath)) {
            verifyStore(directory);
        }
        return IndexShard.open(shardPath, mapperService, translogConfig);
    }

    public static IndexShard recoverFromSnapshot(Path shardPath, MapperService mapperService, Repository repository,
                                                  String snapshotName, ShardId shardId) throws IOException {
        return recoverFromSnapshot(shardPath, mapperService, repository, snapshotName, shardId,
            TranslogConfig.defaultConfig(shardPath.resolve("translog")), RecoveryThrottler.unthrottled());
    }

    public static IndexShard recoverFromSnapshot(Path shardPath, MapperService mapperService, Repository repository,
                                                  String snapshotName, ShardId shardId, TranslogConfig translogConfig,
                                                  RecoveryThrottler throttler) throws IOException {
        Path indexPath = shardPath.resolve("index");
        try (Directory directory = new FSDirectory(indexPath)) {
            DirectoryShardRestoreTarget target = new DirectoryShardRestoreTarget(directory, throttler);
            RestoreRequest request = RestoreRequest.of(List.of(shardId.index()));
            Map<ShardId, ShardRestoreTarget> targets = Map.of(shardId, target);
            repository.restoreSnapshot(snapshotName, request, targets);
        }
        try (Directory directory = new FSDirectory(indexPath)) {
            verifyStore(directory);
        }
        return IndexShard.open(shardPath, mapperService, translogConfig);
    }

    static void verifyStore(Directory directory) throws IOException {
        Set<String> files;
        try {
            files = StoreFiles.latestCommitFileNames(directory);
        } catch (IOException e) {
            throw new CorruptShardException("failed to read segment metadata for store at [" + directory + "]", e);
        }
        for (String name : files) {
            try (IndexInput in = directory.openInput(name, IOContext.READ)) {
                if (name.endsWith("." + Codec.POSTINGS_EXT)) {
                    drain(in);
                } else {
                    CodecUtil.checksumEntireFile(in);
                }
            } catch (IOException e) {
                throw new CorruptShardException("checksum verification failed for file [" + name + "] in store at [" + directory + "]", e);
            }
        }
    }

    private static void drain(IndexInput in) throws IOException {
        long remaining = in.length();
        byte[] buffer = new byte[8192];
        while (remaining > 0) {
            int chunk = (int) Math.min(buffer.length, remaining);
            in.readBytes(buffer, 0, chunk);
            remaining -= chunk;
        }
    }
}
