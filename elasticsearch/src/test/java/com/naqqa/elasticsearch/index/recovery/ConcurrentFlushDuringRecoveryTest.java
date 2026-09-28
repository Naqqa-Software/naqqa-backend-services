package com.naqqa.elasticsearch.index.recovery;

import com.naqqa.elasticsearch.index.mapper.MapperService;
import com.naqqa.elasticsearch.index.shard.IndexShard;
import com.naqqa.elasticsearch.index.translog.Releasable;
import com.naqqa.elasticsearch.store.Directory;
import com.naqqa.elasticsearch.store.FSDirectory;
import com.naqqa.elasticsearch.store.IOContext;
import com.naqqa.elasticsearch.store.IndexInput;
import com.naqqa.elasticsearch.store.IndexOutput;
import com.naqqa.elasticsearch.test.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static com.naqqa.elasticsearch.test.Assert.assertNull;
import static com.naqqa.elasticsearch.test.Assert.assertTrue;

public final class ConcurrentFlushDuringRecoveryTest {

    private static Map<String, Object> doc(String title) {
        return Map.of("title", title);
    }

    private static void copyFile(Directory srcDir, Directory destDir, String name) throws Exception {
        try (IndexInput in = srcDir.openInput(name, IOContext.READ);
             IndexOutput out = destDir.createOutput(name, IOContext.DEFAULT)) {
            long remaining = in.length();
            byte[] buffer = new byte[4096];
            while (remaining > 0) {
                int chunk = (int) Math.min(buffer.length, remaining);
                in.readBytes(buffer, 0, chunk);
                out.writeBytes(buffer, 0, chunk);
                remaining -= chunk;
            }
        }
    }

    @Test
    public void commitFileCopySurvivesConcurrentFlushAndForceMerge() throws Exception {
        Path sourceShardPath = RecoveryTestSupport.newTempShardPath("filecopy-source");
        Path targetShardPath = RecoveryTestSupport.newTempShardPath("filecopy-target");
        Path targetIndexDir = targetShardPath.resolve("index");
        Files.createDirectories(targetIndexDir);
        MapperService mapperService = RecoveryTestSupport.newMapperService();
        IndexShard sourceShard = RecoveryTestSupport.openShard(sourceShardPath, mapperService);
        Directory sourceDirectory = new FSDirectory(sourceShardPath.resolve("index"));

        int initialDocs = 12;
        try {
            for (int i = 0; i < initialDocs; i++) {
                sourceShard.index("doc-" + i, doc("v" + i));
                if (i % 3 == 2) {
                    sourceShard.flush(true);
                }
            }
            sourceShard.flush(true);
            assertTrue(sourceShard.segmentCount() >= 2, "expected multiple segments before the copy starts");

            AtomicBoolean stopChurn = new AtomicBoolean(false);
            AtomicReference<Throwable> churnFailure = new AtomicReference<>();
            Thread churner = new Thread(() -> {
                int i = 0;
                try {
                    while (!stopChurn.get()) {
                        sourceShard.index("churn-" + i, doc("churn" + i));
                        sourceShard.flush(true);
                        if (i % 2 == 0) {
                            sourceShard.forceMerge(1);
                        }
                        i++;
                        Thread.sleep(2);
                    }
                } catch (Throwable t) {
                    churnFailure.set(t);
                }
            }, "source-churner");

            List<StoreFileMetadata> pinnedFiles;
            Releasable commitRef = sourceShard.acquireLastCommitRef();
            try (Directory targetDirectory = new FSDirectory(targetIndexDir)) {
                pinnedFiles = StoreFiles.latestCommitFiles(sourceDirectory);
                churner.start();
                for (StoreFileMetadata file : pinnedFiles) {
                    copyFile(sourceDirectory, targetDirectory, file.name());
                    Thread.sleep(3);
                }
                targetDirectory.sync(pinnedFiles.stream().map(StoreFileMetadata::name).toList());
                for (StoreFileMetadata file : pinnedFiles) {
                    StoreFiles.verifyChecksum(targetDirectory, file);
                }
            } finally {
                commitRef.close();
                stopChurn.set(true);
                churner.join(30_000);
            }
            assertNull(churnFailure.get());

            IndexShard restored = IndexShard.open(targetShardPath, mapperService);
            try {
                assertTrue(restored.docCount() >= initialDocs, "expected the copied commit to contain all the initial docs");
                for (int i = 0; i < initialDocs; i++) {
                    assertTrue(restored.get("doc-" + i).exists(), "expected doc-" + i + " to survive the concurrent copy");
                }
            } finally {
                restored.close();
            }
        } finally {
            sourceShard.close();
            sourceDirectory.close();
        }
    }
}
