package com.naqqa.elasticsearch.snapshots.repository;

import com.naqqa.elasticsearch.snapshots.blobstore.BlobContainer;
import com.naqqa.elasticsearch.snapshots.blobstore.BlobStore;
import com.naqqa.elasticsearch.snapshots.model.ClusterMetadataSnapshot;
import com.naqqa.elasticsearch.snapshots.model.FileInfo;
import com.naqqa.elasticsearch.snapshots.model.ShardId;
import com.naqqa.elasticsearch.snapshots.model.ShardSnapshotManifest;
import com.naqqa.elasticsearch.snapshots.model.ShardSnapshotStats;
import com.naqqa.elasticsearch.snapshots.model.SnapshotInfo;
import com.naqqa.elasticsearch.snapshots.model.SnapshotState;
import com.naqqa.elasticsearch.snapshots.restore.RestoreRequest;
import com.naqqa.elasticsearch.snapshots.restore.RestoreResult;
import com.naqqa.elasticsearch.snapshots.source.RepositoryMetadataSource;
import com.naqqa.elasticsearch.snapshots.source.ShardRestoreTarget;
import com.naqqa.elasticsearch.snapshots.source.ShardSnapshotSource;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class FsRepository implements Repository {

    private final String name;
    private final BlobContainer root;
    private final BlobContainer dataContainer;
    private final ContentAddressedStore contentStore = new ContentAddressedStore();
    private final Object indexLock = new Object();

    public FsRepository(String name, BlobStore blobStore) {
        this.name = name;
        this.root = blobStore.container("");
        this.dataContainer = root.child("data");
    }

    @Override
    public String name() {
        return name;
    }

    private BlobContainer metaContainer() {
        return root.child("meta");
    }

    private BlobContainer shardManifestContainer(ShardId shardId) {
        return root.child("indices/" + shardId.index() + "/" + shardId.shard());
    }

    private RepositoryIndex loadIndex() throws IOException {
        return RepositoryIndexIO.readLatest(root);
    }

    private void saveIndex(List<SnapshotInfo> snapshots) throws IOException {
        synchronized (indexLock) {
            RepositoryIndex current = loadIndex();
            RepositoryIndexIO.writeNext(root, new RepositoryIndex(current.generation() + 1, snapshots));
        }
    }

    @Override
    public SnapshotInfo createSnapshot(CreateSnapshotRequest request) throws IOException {
        if (getSnapshot(request.name()).isPresent()) {
            throw new SnapshotException("snapshot already exists: " + request.name());
        }
        String id = UUID.randomUUID().toString();
        Instant now = request.now() != null ? request.now() : Instant.now();
        long startTimeMillis = now.toEpochMilli();

        Map<String, String> shardManifestBlobs = new LinkedHashMap<>();
        Map<String, ShardSnapshotStats> shardStats = new LinkedHashMap<>();
        boolean anyShardSucceeded = false;
        boolean stoppedEarly = false;
        String failureReason = null;

        List<ShardId> orderedShardIds = new ArrayList<>();
        for (String index : request.indices()) {
            request.shardSources().keySet().stream()
                    .filter(sid -> sid.index().equals(index))
                    .sorted()
                    .forEach(orderedShardIds::add);
        }

        for (ShardId shardId : orderedShardIds) {
            if (request.cancellationToken().isCancelled()) {
                failureReason = "cancelled";
                stoppedEarly = true;
                break;
            }
            ShardSnapshotSource source = request.shardSources().get(shardId);
            List<FileInfo> files = new ArrayList<>();
            int copied = 0;
            int reused = 0;
            long bytesCopied = 0;
            boolean shardCancelled = false;
            for (String fileName : source.listSegmentFiles()) {
                if (request.cancellationToken().isCancelled()) {
                    shardCancelled = true;
                    break;
                }
                String checksum = source.fileChecksum(fileName);
                try (InputStream in = source.openFile(fileName)) {
                    ContentAddressedStore.StoreResult result = contentStore.store(dataContainer, in);
                    if (result.alreadyExisted()) {
                        reused++;
                    } else {
                        copied++;
                        bytesCopied += result.length();
                    }
                    files.add(new FileInfo(fileName, result.blobName(), result.length(), checksum));
                }
            }
            if (shardCancelled) {
                failureReason = "cancelled";
                stoppedEarly = true;
                break;
            }
            ShardSnapshotManifest manifest = new ShardSnapshotManifest(shardId.index(), shardId.shard(), files);
            String manifestBlobName = "snap-" + id + ".json";
            shardManifestContainer(shardId).writeBlob(manifestBlobName, manifest.toBytes(), true);
            shardManifestBlobs.put(shardId.key(), manifestBlobName);
            shardStats.put(shardId.key(), new ShardSnapshotStats(copied, reused, bytesCopied));
            anyShardSucceeded = true;
        }

        String metadataBlob = null;
        if (anyShardSucceeded || !stoppedEarly) {
            ClusterMetadataSnapshot metadata = buildMetadata(request.indices(), request.metadataSource());
            metadataBlob = "meta-" + id + ".json";
            metaContainer().writeBlob(metadataBlob, metadata.toBytes(), true);
        }

        SnapshotState state;
        if (stoppedEarly) {
            state = anyShardSucceeded ? SnapshotState.PARTIAL : SnapshotState.FAILED;
        } else {
            state = SnapshotState.SUCCESS;
        }

        SnapshotInfo info = new SnapshotInfo(id, request.name(), name, request.indices(), state, startTimeMillis,
                Instant.now().toEpochMilli(), failureReason, request.policyId(), shardManifestBlobs, shardStats, metadataBlob);

        synchronized (indexLock) {
            RepositoryIndex current = loadIndex();
            List<SnapshotInfo> updated = new ArrayList<>(current.snapshots());
            updated.add(info);
            RepositoryIndexIO.writeNext(root, new RepositoryIndex(current.generation() + 1, updated));
        }

        return info;
    }

    private ClusterMetadataSnapshot buildMetadata(List<String> indices, RepositoryMetadataSource source) {
        Map<String, Map<String, Object>> settings = new LinkedHashMap<>();
        Map<String, Map<String, Object>> mappings = new LinkedHashMap<>();
        for (String index : indices) {
            if (source.listIndices().contains(index)) {
                settings.put(index, source.indexSettings(index));
                mappings.put(index, source.indexMappings(index));
            }
        }
        return new ClusterMetadataSnapshot(settings, mappings, source.legacyTemplates(), source.indexTemplates(),
                source.componentTemplates(), source.dataStreams());
    }

    @Override
    public Optional<SnapshotInfo> getSnapshot(String snapshotName) {
        try {
            return loadIndex().snapshots().stream().filter(s -> s.name().equals(snapshotName)).findFirst();
        } catch (IOException e) {
            throw new SnapshotException("failed to read repository index", e);
        }
    }

    @Override
    public List<SnapshotInfo> listSnapshots() {
        try {
            return loadIndex().snapshots();
        } catch (IOException e) {
            throw new SnapshotException("failed to read repository index", e);
        }
    }

    @Override
    public void deleteSnapshot(String snapshotName) throws IOException {
        synchronized (indexLock) {
            RepositoryIndex current = loadIndex();
            SnapshotInfo target = current.snapshots().stream()
                    .filter(s -> s.name().equals(snapshotName))
                    .findFirst()
                    .orElseThrow(() -> new SnapshotException("snapshot not found: " + snapshotName));

            for (Map.Entry<String, String> entry : target.shardManifestBlobs().entrySet()) {
                ShardId shardId = ShardId.parseKey(entry.getKey());
                BlobContainer shardContainer = shardManifestContainer(shardId);
                byte[] bytes = shardContainer.readBlobFully(entry.getValue());
                ShardSnapshotManifest manifest = ShardSnapshotManifest.fromBytes(bytes);
                for (FileInfo file : manifest.files()) {
                    contentStore.release(dataContainer, file.blobName());
                }
                shardContainer.deleteBlob(entry.getValue());
            }
            if (target.metadataBlob() != null) {
                metaContainer().deleteBlob(target.metadataBlob());
            }

            List<SnapshotInfo> remaining = new ArrayList<>();
            for (SnapshotInfo s : current.snapshots()) {
                if (!s.id().equals(target.id())) {
                    remaining.add(s);
                }
            }
            RepositoryIndexIO.writeNext(root, new RepositoryIndex(current.generation() + 1, remaining));
        }
    }

    @Override
    public RestoreResult restoreSnapshot(String snapshotName, RestoreRequest request, Map<ShardId, ShardRestoreTarget> shardTargets) throws IOException {
        SnapshotInfo info = getSnapshot(snapshotName)
                .orElseThrow(() -> new SnapshotException("snapshot not found: " + snapshotName));

        List<String> targetIndices;
        if (request.includesAll()) {
            targetIndices = info.indices();
        } else {
            targetIndices = new ArrayList<>();
            for (String requested : request.indices()) {
                if (!info.indices().contains(requested)) {
                    throw new SnapshotException("index not found in snapshot: " + requested);
                }
                targetIndices.add(requested);
            }
        }

        ClusterMetadataSnapshot metadata = info.metadataBlob() != null
                ? ClusterMetadataSnapshot.fromBytes(metaContainer().readBlobFully(info.metadataBlob()))
                : new ClusterMetadataSnapshot(Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of());

        Pattern renamePattern = request.renamePattern() != null ? Pattern.compile(request.renamePattern()) : null;

        Map<String, String> renamedIndices = new LinkedHashMap<>();
        Map<String, Integer> filesRestoredPerIndex = new LinkedHashMap<>();
        Map<String, Map<String, Object>> restoredIndexSettings = new LinkedHashMap<>();

        for (String index : targetIndices) {
            String targetName = index;
            if (renamePattern != null) {
                Matcher matcher = renamePattern.matcher(index);
                if (matcher.matches()) {
                    targetName = matcher.replaceAll(request.renameReplacement());
                }
            }
            if (request.existingIndices().contains(targetName)) {
                throw new SnapshotException("target index already exists: " + targetName);
            }
            renamedIndices.put(index, targetName);

            int filesRestored = 0;
            for (Map.Entry<String, String> entry : info.shardManifestBlobs().entrySet()) {
                ShardId shardId = ShardId.parseKey(entry.getKey());
                if (!shardId.index().equals(index)) {
                    continue;
                }
                byte[] bytes = shardManifestContainer(shardId).readBlobFully(entry.getValue());
                ShardSnapshotManifest manifest = ShardSnapshotManifest.fromBytes(bytes);
                ShardId targetShardId = new ShardId(targetName, shardId.shard());
                ShardRestoreTarget target = shardTargets.get(targetShardId);
                if (target == null) {
                    continue;
                }
                for (FileInfo file : manifest.files()) {
                    try (InputStream in = dataContainer.readBlob(file.blobName());
                            OutputStream out = target.createFile(file.originalName())) {
                        in.transferTo(out);
                    }
                    filesRestored++;
                }
            }
            filesRestoredPerIndex.put(targetName, filesRestored);

            Map<String, Object> merged = new LinkedHashMap<>(metadata.indexSettings().getOrDefault(index, Map.of()));
            merged.putAll(request.indexSettingsOverrides());
            restoredIndexSettings.put(targetName, merged);
        }

        return new RestoreResult(renamedIndices, filesRestoredPerIndex, restoredIndexSettings);
    }

    @Override
    public SnapshotInfo cloneSnapshot(String sourceSnapshotName, String targetSnapshotName) throws IOException {
        synchronized (indexLock) {
            SnapshotInfo source = getSnapshot(sourceSnapshotName)
                    .orElseThrow(() -> new SnapshotException("snapshot not found: " + sourceSnapshotName));
            if (getSnapshot(targetSnapshotName).isPresent()) {
                throw new SnapshotException("snapshot already exists: " + targetSnapshotName);
            }

            String newId = UUID.randomUUID().toString();
            Map<String, String> newShardManifestBlobs = new LinkedHashMap<>();
            for (Map.Entry<String, String> entry : source.shardManifestBlobs().entrySet()) {
                ShardId shardId = ShardId.parseKey(entry.getKey());
                BlobContainer shardContainer = shardManifestContainer(shardId);
                byte[] bytes = shardContainer.readBlobFully(entry.getValue());
                ShardSnapshotManifest manifest = ShardSnapshotManifest.fromBytes(bytes);
                for (FileInfo file : manifest.files()) {
                    contentStore.addReference(dataContainer, file.blobName());
                }
                String newManifestBlobName = "snap-" + newId + ".json";
                shardContainer.writeBlob(newManifestBlobName, manifest.toBytes(), true);
                newShardManifestBlobs.put(entry.getKey(), newManifestBlobName);
            }

            String newMetadataBlob = null;
            if (source.metadataBlob() != null) {
                byte[] metaBytes = metaContainer().readBlobFully(source.metadataBlob());
                newMetadataBlob = "meta-" + newId + ".json";
                metaContainer().writeBlob(newMetadataBlob, metaBytes, true);
            }

            long now = Instant.now().toEpochMilli();
            SnapshotInfo cloned = new SnapshotInfo(newId, targetSnapshotName, name, source.indices(), source.state(),
                    now, now, source.failureReason(), source.policyId(), newShardManifestBlobs, source.shardStats(), newMetadataBlob);

            RepositoryIndex current = loadIndex();
            List<SnapshotInfo> updated = new ArrayList<>(current.snapshots());
            updated.add(cloned);
            RepositoryIndexIO.writeNext(root, new RepositoryIndex(current.generation() + 1, updated));
            return cloned;
        }
    }

    @Override
    public void verify() throws IOException {
        String probe = "verify-" + UUID.randomUUID();
        byte[] payload = "ok".getBytes(StandardCharsets.UTF_8);
        root.writeBlob(probe, payload, true);
        byte[] readBack = root.readBlobFully(probe);
        root.deleteBlob(probe);
        if (!Arrays.equals(payload, readBack)) {
            throw new SnapshotException("repository verification failed for " + name);
        }
        loadIndex();
    }
}
