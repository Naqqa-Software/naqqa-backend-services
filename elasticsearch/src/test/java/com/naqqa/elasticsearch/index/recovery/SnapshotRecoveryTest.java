package com.naqqa.elasticsearch.index.recovery;

import com.naqqa.elasticsearch.index.mapper.MapperService;
import com.naqqa.elasticsearch.index.shard.IndexShard;
import com.naqqa.elasticsearch.snapshots.blobstore.FsBlobStore;
import com.naqqa.elasticsearch.snapshots.model.ShardId;
import com.naqqa.elasticsearch.snapshots.repository.CreateSnapshotRequest;
import com.naqqa.elasticsearch.snapshots.repository.FsRepository;
import com.naqqa.elasticsearch.snapshots.source.InMemoryRepositoryMetadataSource;
import com.naqqa.elasticsearch.snapshots.source.ShardSnapshotSource;
import com.naqqa.elasticsearch.store.Directory;
import com.naqqa.elasticsearch.store.FSDirectory;
import com.naqqa.elasticsearch.store.IOContext;
import com.naqqa.elasticsearch.store.IndexInput;
import com.naqqa.elasticsearch.test.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.naqqa.elasticsearch.test.Assert.assertEquals;
import static com.naqqa.elasticsearch.test.Assert.assertTrue;

public final class SnapshotRecoveryTest {

    private static final class DirectoryShardSnapshotSource implements ShardSnapshotSource {
        private final Directory directory;
        private final List<String> files;

        DirectoryShardSnapshotSource(Directory directory) throws IOException {
            this.directory = directory;
            this.files = new ArrayList<>();
            for (StoreFileMetadata f : StoreFiles.latestCommitFiles(directory)) {
                files.add(f.name());
            }
        }

        @Override
        public List<String> listSegmentFiles() {
            return files;
        }

        @Override
        public InputStream openFile(String name) throws IOException {
            IndexInput in = directory.openInput(name, IOContext.READ);
            return new InputStream() {
                private final long length = in.length();
                private long pos = 0;

                @Override
                public int read() throws IOException {
                    if (pos >= length) {
                        return -1;
                    }
                    pos++;
                    return in.readByte() & 0xFF;
                }

                @Override
                public int read(byte[] b, int off, int len) throws IOException {
                    if (pos >= length) {
                        return -1;
                    }
                    int toRead = (int) Math.min(len, length - pos);
                    in.readBytes(b, off, toRead);
                    pos += toRead;
                    return toRead;
                }

                @Override
                public void close() throws IOException {
                    in.close();
                }
            };
        }

        @Override
        public long fileLength(String name) {
            try {
                return directory.fileLength(name);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public String fileChecksum(String name) {
            try {
                return Long.toHexString(StoreFiles.checksumOf(directory, name));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    @Test
    public void snapshotRecoveryRestoresShardFromFsRepository() throws Exception {
        Path sourcePath = RecoveryTestSupport.newTempShardPath("snap-source");
        Path targetPath = RecoveryTestSupport.newTempShardPath("snap-target");
        Path repoPath = Files.createTempDirectory("snap-repo");
        MapperService mapperService = RecoveryTestSupport.newMapperService();

        IndexShard sourceShard = RecoveryTestSupport.openShard(sourcePath, mapperService);
        for (int i = 0; i < 6; i++) {
            sourceShard.index("doc-" + i, Map.of("title", "v" + i));
        }
        sourceShard.flush(true);

        ShardId shardId = new ShardId("test-index", 0);
        FsRepository repository = new FsRepository("repo", new FsBlobStore(repoPath));
        try (Directory sourceDirectory = new FSDirectory(sourcePath.resolve("index"))) {
            ShardSnapshotSource snapshotSource = new DirectoryShardSnapshotSource(sourceDirectory);
            CreateSnapshotRequest request = CreateSnapshotRequest.of("snap1", List.of("test-index"),
                Map.of(shardId, snapshotSource), new InMemoryRepositoryMetadataSource());
            repository.createSnapshot(request);
        }
        sourceShard.close();

        IndexShard restored = StoreRecovery.recoverFromSnapshot(targetPath, RecoveryTestSupport.newMapperService(), repository, "snap1", shardId);
        try {
            assertEquals(6, restored.docCount());
            for (int i = 0; i < 6; i++) {
                assertTrue(restored.get("doc-" + i).exists(), "expected doc-" + i + " to be restored from snapshot");
            }
        } finally {
            restored.close();
        }
    }
}
