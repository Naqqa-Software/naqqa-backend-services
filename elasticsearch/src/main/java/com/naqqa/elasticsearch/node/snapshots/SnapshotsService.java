package com.naqqa.elasticsearch.node.snapshots;

import com.naqqa.elasticsearch.cluster.state.ClusterState;
import com.naqqa.elasticsearch.cluster.state.IndexMetadata;
import com.naqqa.elasticsearch.common.json.JsonValue;
import com.naqqa.elasticsearch.index.shard.IndexShard;
import com.naqqa.elasticsearch.node.action.NodeIndexAdminActionService;
import com.naqqa.elasticsearch.node.cluster.ClusterStateManager;
import com.naqqa.elasticsearch.node.indices.IndexService;
import com.naqqa.elasticsearch.node.indices.IndicesService;
import com.naqqa.elasticsearch.node.indices.MetadataIndexService;
import com.naqqa.elasticsearch.node.support.IndexResolver;
import com.naqqa.elasticsearch.node.support.SettingsMaps;
import com.naqqa.elasticsearch.rest.support.RestApiException;
import com.naqqa.elasticsearch.snapshots.blobstore.FsBlobStore;
import com.naqqa.elasticsearch.snapshots.model.ShardId;
import com.naqqa.elasticsearch.snapshots.model.SnapshotInfo;
import com.naqqa.elasticsearch.snapshots.repository.CreateSnapshotRequest;
import com.naqqa.elasticsearch.snapshots.repository.FsRepository;
import com.naqqa.elasticsearch.snapshots.repository.Repository;
import com.naqqa.elasticsearch.snapshots.restore.RestoreRequest;
import com.naqqa.elasticsearch.snapshots.restore.RestoreResult;
import com.naqqa.elasticsearch.snapshots.source.RepositoryMetadataSource;
import com.naqqa.elasticsearch.snapshots.source.ShardRestoreTarget;
import com.naqqa.elasticsearch.snapshots.source.ShardSnapshotSource;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import java.util.zip.CRC32;

public final class SnapshotsService {

    public static final String MAPPINGS_SETTING = "index.snapshot_source_mappings";

    public record RepositoryEntry(String name, String type, Map<String, Object> settings, Repository repository) {
    }

    private final ClusterStateManager clusterStateManager;
    private final IndicesService indicesService;
    private final NodeIndexAdminActionService indexAdmin;
    private final IndexResolver indexResolver;
    private final Path indicesPath;
    private final List<Path> repoRoots;
    private final Path registryFile;
    private final Map<String, RepositoryEntry> repositories = new ConcurrentHashMap<>();

    public SnapshotsService(ClusterStateManager clusterStateManager, IndicesService indicesService,
                            NodeIndexAdminActionService indexAdmin, Path indicesPath, List<Path> repoRoots, Path registryFile) {
        this.clusterStateManager = clusterStateManager;
        this.indicesService = indicesService;
        this.indexAdmin = indexAdmin;
        this.indexResolver = indexAdmin.indexResolver();
        this.indicesPath = indicesPath;
        this.repoRoots = repoRoots;
        this.registryFile = registryFile;
        loadRegistry();
    }

    public Map<String, RepositoryEntry> repositories() {
        return repositories;
    }

    @SuppressWarnings("unchecked")
    private void loadRegistry() {
        if (!Files.exists(registryFile)) {
            return;
        }
        try {
            Object parsed = JsonValue.parse(Files.readAllBytes(registryFile)).toJava();
            Map<String, Object> map = SettingsMaps.asMap(parsed);
            if (map == null) {
                return;
            }
            for (Map.Entry<String, Object> e : map.entrySet()) {
                Map<String, Object> def = SettingsMaps.asMap(e.getValue());
                if (def == null) {
                    continue;
                }
                try {
                    register(e.getKey(), String.valueOf(def.get("type")), SettingsMaps.asMap(def.get("settings")), false, false);
                } catch (RuntimeException ex) {
                    System.err.println("[snapshots] failed to restore repository [" + e.getKey() + "]: " + ex.getMessage());
                }
            }
        } catch (IOException | RuntimeException e) {
            System.err.println("[snapshots] failed to read repository registry: " + e);
        }
    }

    private synchronized void saveRegistry() {
        Map<String, Object> out = new TreeMap<>();
        for (RepositoryEntry entry : repositories.values()) {
            out.put(entry.name(), Map.of("type", entry.type(), "settings", entry.settings()));
        }
        try {
            Files.createDirectories(registryFile.getParent());
            Path tmp = registryFile.resolveSibling(registryFile.getFileName() + ".tmp");
            Files.write(tmp, JsonValue.wrap(out).toString().getBytes(StandardCharsets.UTF_8));
            Files.move(tmp, registryFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new RestApiException(500, "failed to persist repository registry: " + e.getMessage(), e);
        }
    }

    private Path resolveLocation(String location) {
        Path candidate = Path.of(location);
        if (!candidate.isAbsolute()) {
            if (repoRoots.isEmpty()) {
                throw new RestApiException(500, "[path.repo] is not configured; cannot resolve relative location [" + location + "]");
            }
            candidate = repoRoots.get(0).resolve(location);
        }
        Path normalized = candidate.toAbsolutePath().normalize();
        for (Path root : repoRoots) {
            if (normalized.startsWith(root.toAbsolutePath().normalize())) {
                return normalized;
            }
        }
        throw new RestApiException(500, "location [" + location + "] doesn't match any of the locations specified by path.repo "
            + repoRoots);
    }

    public void register(String name, String type, Map<String, Object> settings, boolean verify, boolean persist) {
        if (name == null || name.isEmpty() || name.startsWith("_") || name.contains(" ")) {
            throw new RestApiException(400, "Invalid repository name [" + name + "]");
        }
        if (!"fs".equals(type)) {
            throw new RestApiException(500, "repository type [" + type + "] does not exist");
        }
        Map<String, Object> s = settings == null ? Map.of() : settings;
        if (s.get("location") == null) {
            throw new RestApiException(500, "[" + name + "] missing location");
        }
        Path location = resolveLocation(String.valueOf(s.get("location")));
        try {
            Files.createDirectories(location);
        } catch (IOException e) {
            throw new RestApiException(500, "[" + name + "] cannot create location [" + location + "]: " + e.getMessage(), e);
        }
        FsRepository repository = new FsRepository(name, new FsBlobStore(location));
        if (verify) {
            try {
                repository.verify();
            } catch (IOException | RuntimeException e) {
                throw new RestApiException(500, "[" + name + "] path is not accessible on this node: " + e.getMessage(), e);
            }
        }
        repositories.put(name, new RepositoryEntry(name, type, new LinkedHashMap<>(s), repository));
        if (persist) {
            saveRegistry();
        }
    }

    public void deleteRepository(String name) {
        if (repositories.remove(name) == null) {
            throw new RestApiException(404, "[" + name + "] missing");
        }
        saveRegistry();
    }

    public RepositoryEntry repository(String name) {
        RepositoryEntry entry = repositories.get(name);
        if (entry == null) {
            throw new RestApiException(404, "[" + name + "] missing");
        }
        return entry;
    }

    public void verify(String name) {
        try {
            repository(name).repository().verify();
        } catch (IOException e) {
            throw new RestApiException(500, "[" + name + "] verification failed: " + e.getMessage(), e);
        }
    }

    public SnapshotInfo createSnapshot(String repositoryName, String snapshotName, List<String> indexExpressions) {
        RepositoryEntry entry = repository(repositoryName);
        if (entry.repository().getSnapshot(snapshotName).isPresent()) {
            throw new RestApiException(400, "[" + repositoryName + ":" + snapshotName + "] Invalid snapshot name ["
                + snapshotName + "], snapshot with the same name already exists");
        }
        ClusterState state = clusterStateManager.state();
        List<String> indices = indexResolver.resolve(state, indexExpressions, false, false);
        Map<ShardId, ShardSnapshotSource> sources = new LinkedHashMap<>();
        for (String index : indices) {
            IndexService service = indicesService.indexService(index);
            if (service == null) {
                throw new RestApiException(500, "index [" + index + "] has no local shards to snapshot");
            }
            for (Map.Entry<Integer, IndexShard> e : new TreeMap<>(service.shards()).entrySet()) {
                try {
                    e.getValue().flush(true);
                } catch (IOException ex) {
                    throw new RestApiException(500, "failed to flush " + index + "[" + e.getKey() + "]: " + ex.getMessage(), ex);
                }
                sources.put(new ShardId(index, e.getKey()), new DirectoryShardSnapshotSource(service.shardPath(e.getKey()).resolve("index")));
            }
        }
        try {
            return entry.repository().createSnapshot(new CreateSnapshotRequest(snapshotName, indices, sources,
                new ClusterMetadataSource(state, indices), null, null, Instant.now()));
        } catch (IOException e) {
            throw new RestApiException(500, "snapshot [" + repositoryName + ":" + snapshotName + "] failed: " + e.getMessage(), e);
        }
    }

    public List<SnapshotInfo> snapshots(String repositoryName) {
        return repository(repositoryName).repository().listSnapshots();
    }

    public Optional<SnapshotInfo> snapshot(String repositoryName, String snapshotName) {
        return repository(repositoryName).repository().getSnapshot(snapshotName);
    }

    public void deleteSnapshot(String repositoryName, String snapshotName) {
        RepositoryEntry entry = repository(repositoryName);
        if (entry.repository().getSnapshot(snapshotName).isEmpty()) {
            throw new RestApiException(404, "[" + repositoryName + ":" + snapshotName + "] is missing");
        }
        try {
            entry.repository().deleteSnapshot(snapshotName);
        } catch (IOException e) {
            throw new RestApiException(500, e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> restore(String repositoryName, String snapshotName, Map<String, Object> body) {
        RepositoryEntry entry = repository(repositoryName);
        SnapshotInfo info = entry.repository().getSnapshot(snapshotName)
            .orElseThrow(() -> new RestApiException(404, "[" + repositoryName + ":" + snapshotName + "] is missing"));
        Map<String, Object> b = body == null ? Map.of() : body;
        List<String> requested = SettingsMaps.asStringList(b.get("indices"));
        List<String> selected = new ArrayList<>();
        for (String index : info.indices()) {
            if (requested.isEmpty() || requested.stream().anyMatch(p -> com.naqqa.elasticsearch.common.regex.Regex.simpleMatch(p, index))) {
                selected.add(index);
            }
        }
        String renamePattern = b.get("rename_pattern") == null ? null : String.valueOf(b.get("rename_pattern"));
        String renameReplacement = b.get("rename_replacement") == null ? null : String.valueOf(b.get("rename_replacement"));
        Map<String, Object> overrides = SettingsMaps.asMap(b.get("index_settings"));
        ClusterState state = clusterStateManager.state();
        Set<String> existing = new LinkedHashSet<>(state.getMetadata().getIndices().keySet());
        Map<String, String> targetNames = new LinkedHashMap<>();
        java.util.regex.Pattern pattern = renamePattern == null ? null : java.util.regex.Pattern.compile(renamePattern);
        for (String index : selected) {
            String target = index;
            if (pattern != null) {
                java.util.regex.Matcher m = pattern.matcher(index);
                if (m.matches()) {
                    target = m.replaceAll(renameReplacement == null ? "" : renameReplacement);
                }
            }
            if (existing.contains(target)) {
                throw new RestApiException(500, "[" + repositoryName + ":" + snapshotName + "] cannot restore index [" + target
                    + "] because an open index with same name already exists in the cluster");
            }
            targetNames.put(index, target);
        }
        Map<String, String> uuids = new LinkedHashMap<>();
        Map<ShardId, ShardRestoreTarget> targets = new LinkedHashMap<>();
        for (String key : info.shardManifestBlobs().keySet()) {
            ShardId shardId = ShardId.parseKey(key);
            String target = targetNames.get(shardId.index());
            if (target == null) {
                continue;
            }
            String uuid = uuids.computeIfAbsent(target, t -> MetadataIndexService.newUuid());
            Path dir = indicesPath.resolve(uuid).resolve(Integer.toString(shardId.shard())).resolve("index");
            targets.put(new ShardId(target, shardId.shard()), name -> {
                Files.createDirectories(dir);
                return Files.newOutputStream(dir.resolve(name));
            });
        }
        RestoreResult result;
        try {
            result = entry.repository().restoreSnapshot(snapshotName,
                new RestoreRequest(selected, renamePattern, renameReplacement, overrides, existing), targets);
        } catch (IOException | RuntimeException e) {
            for (String uuid : uuids.values()) {
                deleteQuietly(indicesPath.resolve(uuid));
            }
            throw new RestApiException(500, "restore of [" + repositoryName + ":" + snapshotName + "] failed: " + e.getMessage(), e);
        }
        List<String> restored = new ArrayList<>();
        for (Map.Entry<String, String> e : result.renamedIndices().entrySet()) {
            String target = e.getValue();
            Map<String, Object> settings = new LinkedHashMap<>(result.restoredIndexSettings().getOrDefault(target, Map.of()));
            Object mappingsJson = settings.remove(MAPPINGS_SETTING);
            Map<String, Object> mappings = mappingsJson == null ? Map.of()
                : SettingsMaps.asMap(JsonValue.parse(String.valueOf(mappingsJson).getBytes(StandardCharsets.UTF_8)).toJava());
            settings.keySet().removeIf(k -> k.equals("index.uuid") || k.equals("index.creation_date") || k.equals("index.provided_name"));
            String uuid = uuids.computeIfAbsent(target, t -> MetadataIndexService.newUuid());
            indexAdmin.createIndexInternal(target, settings, mappings, Map.of(), uuid, true);
            restored.add(target);
        }
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("snapshot", snapshotName);
        snapshot.put("indices", restored);
        snapshot.put("shards", Map.of("total", targets.size(), "failed", 0, "successful", targets.size()));
        return Map.of("snapshot", snapshot);
    }

    private static void deleteQuietly(Path path) {
        if (!Files.exists(path)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(path)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }

    public Map<String, Object> render(SnapshotInfo info) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("snapshot", info.name());
        m.put("uuid", info.id());
        m.put("repository", info.repository());
        m.put("indices", info.indices());
        m.put("state", info.state().name());
        m.put("start_time_in_millis", info.startTimeMillis());
        if (info.endTimeMillis() != null) {
            m.put("end_time_in_millis", info.endTimeMillis());
            m.put("duration_in_millis", info.endTimeMillis() - info.startTimeMillis());
        }
        if (info.failureReason() != null) {
            m.put("reason", info.failureReason());
        }
        int total = info.shardManifestBlobs().size();
        m.put("shards", Map.of("total", total, "failed", 0, "successful", total));
        return m;
    }

    private static final class DirectoryShardSnapshotSource implements ShardSnapshotSource {
        private final Path dir;
        private final Map<String, String> checksums = new ConcurrentHashMap<>();

        DirectoryShardSnapshotSource(Path dir) {
            this.dir = dir;
        }

        @Override
        public List<String> listSegmentFiles() {
            List<String> out = new ArrayList<>();
            if (!Files.isDirectory(dir)) {
                return out;
            }
            try (Stream<Path> files = Files.list(dir)) {
                files.filter(Files::isRegularFile).map(p -> p.getFileName().toString())
                    .filter(n -> !n.equals("write.lock") && !n.endsWith(".tmp")).sorted().forEach(out::add);
            } catch (IOException e) {
                throw new RestApiException(500, "failed to list shard files in [" + dir + "]: " + e.getMessage(), e);
            }
            return out;
        }

        @Override
        public InputStream openFile(String name) throws IOException {
            return Files.newInputStream(dir.resolve(name));
        }

        @Override
        public long fileLength(String name) {
            try {
                return Files.size(dir.resolve(name));
            } catch (IOException e) {
                return -1L;
            }
        }

        @Override
        public String fileChecksum(String name) {
            return checksums.computeIfAbsent(name, n -> {
                CRC32 crc = new CRC32();
                try (InputStream in = Files.newInputStream(dir.resolve(n))) {
                    byte[] buffer = new byte[65536];
                    int read;
                    while ((read = in.read(buffer)) > 0) {
                        crc.update(buffer, 0, read);
                    }
                } catch (IOException e) {
                    throw new RestApiException(500, "failed to checksum [" + n + "]: " + e.getMessage(), e);
                }
                return Long.toHexString(crc.getValue());
            });
        }
    }

    private final class ClusterMetadataSource implements RepositoryMetadataSource {
        private final ClusterState state;
        private final List<String> indices;

        ClusterMetadataSource(ClusterState state, List<String> indices) {
            this.state = state;
            this.indices = indices;
        }

        @Override
        public Set<String> listIndices() {
            return new LinkedHashSet<>(indices);
        }

        @Override
        public Map<String, Object> indexSettings(String index) {
            IndexMetadata imd = state.getMetadata().index(index);
            Map<String, Object> out = new LinkedHashMap<>();
            if (imd == null) {
                return out;
            }
            out.putAll(imd.getSettings().getAsMap());
            IndexService service = indicesService.indexService(index);
            Map<String, Object> mappings = imd.getMappings();
            if (service != null && service.mapperService().documentMapper() != null) {
                Map<String, Object> live = SettingsMaps.asMap(service.mapperService().documentMapper().mapping().toMapping().toJava());
                if (live != null) {
                    mappings = live;
                }
            }
            out.put(MAPPINGS_SETTING, JsonValue.wrap(mappings).toString());
            return out;
        }

        @Override
        public Map<String, Object> indexMappings(String index) {
            IndexMetadata imd = state.getMetadata().index(index);
            return imd == null ? Map.of() : new LinkedHashMap<>(imd.getMappings());
        }

        @Override
        public Map<String, Object> legacyTemplates() {
            return new LinkedHashMap<>(indexAdmin.legacyTemplateSources());
        }

        @Override
        public Map<String, Object> indexTemplates() {
            return new LinkedHashMap<>(indexAdmin.indexTemplateSources());
        }

        @Override
        public Map<String, Object> componentTemplates() {
            return new LinkedHashMap<>(indexAdmin.componentTemplateSources());
        }

        @Override
        public Map<String, Object> dataStreams() {
            Map<String, Object> out = new LinkedHashMap<>();
            for (String ds : indexAdmin.dataStreamNames()) {
                out.put(ds, indexAdmin.dataStreamBackingIndices(ds));
            }
            return out;
        }
    }
}
