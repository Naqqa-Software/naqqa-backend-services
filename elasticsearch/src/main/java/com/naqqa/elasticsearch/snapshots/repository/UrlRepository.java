package com.naqqa.elasticsearch.snapshots.repository;

import com.naqqa.elasticsearch.snapshots.model.ClusterMetadataSnapshot;
import com.naqqa.elasticsearch.snapshots.model.FileInfo;
import com.naqqa.elasticsearch.snapshots.model.ShardId;
import com.naqqa.elasticsearch.snapshots.model.ShardSnapshotManifest;
import com.naqqa.elasticsearch.snapshots.restore.RestoreRequest;
import com.naqqa.elasticsearch.snapshots.restore.RestoreResult;
import com.naqqa.elasticsearch.snapshots.source.ShardRestoreTarget;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class UrlRepository implements Repository {

    private final String name;
    private final URI base;
    private final HttpClient httpClient;

    public UrlRepository(String name, URI base) {
        this.name = name;
        this.base = base.toString().endsWith("/") ? base : URI.create(base + "/");
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    @Override
    public String name() {
        return name;
    }

    private byte[] get(String relativePath) throws IOException {
        try {
            HttpRequest request = HttpRequest.newBuilder(base.resolve(relativePath)).GET().build();
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                throw new IOException("HTTP " + response.statusCode() + " fetching " + relativePath);
            }
            return response.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(e);
        }
    }

    private RepositoryIndex readLatest() throws IOException {
        byte[] pointer = get("index.latest");
        long generation = Long.parseLong(new String(pointer, StandardCharsets.UTF_8).trim());
        return RepositoryIndex.fromBytes(get("index-" + generation));
    }

    @Override
    public com.naqqa.elasticsearch.snapshots.model.SnapshotInfo createSnapshot(CreateSnapshotRequest request) {
        throw new UnsupportedOperationException("url repository " + name + " is read-only");
    }

    @Override
    public Optional<com.naqqa.elasticsearch.snapshots.model.SnapshotInfo> getSnapshot(String snapshotName) {
        try {
            return readLatest().snapshots().stream().filter(s -> s.name().equals(snapshotName)).findFirst();
        } catch (IOException e) {
            throw new SnapshotException("failed to read url repository index", e);
        }
    }

    @Override
    public List<com.naqqa.elasticsearch.snapshots.model.SnapshotInfo> listSnapshots() {
        try {
            return readLatest().snapshots();
        } catch (IOException e) {
            throw new SnapshotException("failed to read url repository index", e);
        }
    }

    @Override
    public void deleteSnapshot(String snapshotName) {
        throw new UnsupportedOperationException("url repository " + name + " is read-only");
    }

    @Override
    public RestoreResult restoreSnapshot(String snapshotName, RestoreRequest request, Map<ShardId, ShardRestoreTarget> shardTargets) throws IOException {
        com.naqqa.elasticsearch.snapshots.model.SnapshotInfo info = getSnapshot(snapshotName)
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
                ? ClusterMetadataSnapshot.fromBytes(get("meta/" + info.metadataBlob()))
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
                byte[] bytes = get("indices/" + shardId.index() + "/" + shardId.shard() + "/" + entry.getValue());
                ShardSnapshotManifest manifest = ShardSnapshotManifest.fromBytes(bytes);
                ShardId targetShardId = new ShardId(targetName, shardId.shard());
                ShardRestoreTarget target = shardTargets.get(targetShardId);
                if (target == null) {
                    continue;
                }
                for (FileInfo file : manifest.files()) {
                    byte[] content = get("data/" + file.blobName());
                    try (InputStream in = new java.io.ByteArrayInputStream(content);
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
    public com.naqqa.elasticsearch.snapshots.model.SnapshotInfo cloneSnapshot(String sourceSnapshotName, String targetSnapshotName) {
        throw new UnsupportedOperationException("url repository " + name + " is read-only");
    }

    @Override
    public void verify() throws IOException {
        readLatest();
    }
}
