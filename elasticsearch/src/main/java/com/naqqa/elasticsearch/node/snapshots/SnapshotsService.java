package com.naqqa.elasticsearch.node.snapshots;

import com.naqqa.elasticsearch.cluster.node.DiscoveryNode;
import com.naqqa.elasticsearch.cluster.routing.IndexRoutingTable;
import com.naqqa.elasticsearch.cluster.routing.IndexShardRoutingTable;
import com.naqqa.elasticsearch.cluster.routing.ShardRouting;
import com.naqqa.elasticsearch.cluster.service.ClusterChangedEvent;
import com.naqqa.elasticsearch.cluster.service.ClusterStateListener;
import com.naqqa.elasticsearch.cluster.state.ClusterState;
import com.naqqa.elasticsearch.cluster.state.IndexMetadata;
import com.naqqa.elasticsearch.cluster.state.MapCustom;
import com.naqqa.elasticsearch.cluster.state.Metadata;
import com.naqqa.elasticsearch.common.json.JsonValue;
import com.naqqa.elasticsearch.common.logging.ESLogger;
import com.naqqa.elasticsearch.index.shard.IndexShard;
import com.naqqa.elasticsearch.index.translog.Releasable;
import com.naqqa.elasticsearch.node.action.NodeIndexAdminActionService;
import com.naqqa.elasticsearch.node.cluster.ClusterStateManager;
import com.naqqa.elasticsearch.node.cluster.NodeConnections;
import com.naqqa.elasticsearch.node.cluster.Wire;
import com.naqqa.elasticsearch.node.indices.IndexService;
import com.naqqa.elasticsearch.node.indices.IndicesService;
import com.naqqa.elasticsearch.node.indices.MetadataIndexService;
import com.naqqa.elasticsearch.node.support.IndexResolver;
import com.naqqa.elasticsearch.node.support.SettingsMaps;
import com.naqqa.elasticsearch.rest.support.RestApiException;
import com.naqqa.elasticsearch.transport.TransportService;
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

public final class SnapshotsService implements ClusterStateListener {

    private static final ESLogger LOG = ESLogger.getLogger(SnapshotsService.class);

    public static final String MAPPINGS_SETTING = "index.snapshot_source_mappings";
    public static final String REPOSITORIES_CUSTOM = "snapshot_repositories";
    public static final String SHARD_LIST_ACTION = "internal:admin/snapshot/shard_list";
    public static final String SHARD_FILE_ACTION = "internal:admin/snapshot/shard_file";
    public static final String SHARD_WRITE_ACTION = "internal:admin/snapshot/shard_write";

    public record RepositoryEntry(String name, String type, Map<String, Object> settings, Repository repository) {
    }

    private final ClusterStateManager clusterStateManager;
    private final IndicesService indicesService;
    private final NodeIndexAdminActionService indexAdmin;
    private final IndexResolver indexResolver;
    private final Path indicesPath;
    private final List<Path> repoRoots;
    private final Map<String, RepositoryEntry> repositories = new ConcurrentHashMap<>();
    private final TransportService transportService;
    private final NodeConnections connections;

    public SnapshotsService(ClusterStateManager clusterStateManager, IndicesService indicesService,
                            NodeIndexAdminActionService indexAdmin, Path indicesPath, List<Path> repoRoots, Path registryFile) {
        this(clusterStateManager, indicesService, indexAdmin, indicesPath, repoRoots, registryFile, null, null);
    }

    public SnapshotsService(ClusterStateManager clusterStateManager, IndicesService indicesService,
                            NodeIndexAdminActionService indexAdmin, Path indicesPath, List<Path> repoRoots, Path registryFile,
                            TransportService transportService, NodeConnections connections) {
        this.clusterStateManager = clusterStateManager;
        this.indicesService = indicesService;
        this.indexAdmin = indexAdmin;
        this.indexResolver = indexAdmin.indexResolver();
        this.indicesPath = indicesPath;
        this.repoRoots = repoRoots;
        this.transportService = transportService;
        this.connections = connections;
        if (transportService != null) {
            Wire.register(transportService, SHARD_LIST_ACTION, this::handleShardList);
            Wire.register(transportService, SHARD_FILE_ACTION, this::handleShardFile);
            Wire.register(transportService, SHARD_WRITE_ACTION, this::handleShardWrite);
        }
        syncFromClusterState(clusterStateManager.state().getMetadata());
        clusterStateManager.addListener(this);
    }

    private java.util.concurrent.CompletableFuture<byte[]> handleShardList(byte[] payload) throws IOException {
        Map<String, Object> req = Wire.decode(payload);
        String index = String.valueOf(req.get("index"));
        int shard = ((Number) req.get("shard")).intValue();
        IndexService service = indicesService.indexService(index);
        List<String> files = List.of();
        if (service != null) {
            IndexShard indexShard = service.shard(shard);
            if (indexShard != null) {
                indexShard.flush(true);
            }
            files = new DirectoryShardSnapshotSource(service.shardPath(shard).resolve("index")).listSegmentFiles();
        }
        return java.util.concurrent.CompletableFuture.completedFuture(Wire.encode(Map.of("files", files)));
    }

    private java.util.concurrent.CompletableFuture<byte[]> handleShardFile(byte[] payload) throws IOException {
        Map<String, Object> req = Wire.decode(payload);
        String index = String.valueOf(req.get("index"));
        int shard = ((Number) req.get("shard")).intValue();
        String name = String.valueOf(req.get("name"));
        IndexService service = indicesService.indexService(index);
        if (service == null) {
            throw new RestApiException(500, "index [" + index + "] has no local shard [" + shard + "] to read for snapshot");
        }
        byte[] data = Files.readAllBytes(service.shardPath(shard).resolve("index").resolve(name));
        return java.util.concurrent.CompletableFuture.completedFuture(Wire.encode(Map.of("data", data)));
    }

    private java.util.concurrent.CompletableFuture<byte[]> handleShardWrite(byte[] payload) throws IOException {
        Map<String, Object> req = Wire.decode(payload);
        String uuid = String.valueOf(req.get("uuid"));
        int shard = ((Number) req.get("shard")).intValue();
        String name = String.valueOf(req.get("name"));
        byte[] data = (byte[]) req.get("data");
        Path dir = indicesPath.resolve(uuid).resolve(Integer.toString(shard)).resolve("index");
        Files.createDirectories(dir);
        Files.write(dir.resolve(name), data);
        return java.util.concurrent.CompletableFuture.completedFuture(Wire.encode(Map.of("ok", true)));
    }

    private DiscoveryNode nodeHoldingShard(ClusterState state, String index, int shardNum) {
        IndexRoutingTable irt = state.getRoutingTable().index(index);
        IndexShardRoutingTable table = irt == null ? null : irt.shard(shardNum);
        if (table == null) {
            return null;
        }
        ShardRouting primary = table.primaryShard();
        if (primary != null && primary.active() && primary.currentNodeId() != null) {
            return state.getNodes().get(primary.currentNodeId());
        }
        for (ShardRouting sr : table.getShards()) {
            if (sr.active() && sr.currentNodeId() != null) {
                return state.getNodes().get(sr.currentNodeId());
            }
        }
        return null;
    }

    public Map<String, RepositoryEntry> repositories() {
        return repositories;
    }

    @Override
    public void clusterChanged(ClusterChangedEvent event) {
        if (event.state().getMetadata() != event.previousState().getMetadata()) {
            syncFromClusterState(event.state().getMetadata());
        }
    }

    private void syncFromClusterState(Metadata metadata) {
        MapCustom custom = metadata.mapCustom(REPOSITORIES_CUSTOM);
        for (String name : new ArrayList<>(repositories.keySet())) {
            if (!custom.contains(name)) {
                repositories.remove(name);
            }
        }
        for (String name : custom.ids()) {
            Map<String, Object> def = custom.get(name);
            RepositoryEntry existing = repositories.get(name);
            String type = String.valueOf(def.get("type"));
            Map<String, Object> settings = SettingsMaps.asMap(def.get("settings"));
            if (existing != null && existing.type().equals(type) && existing.settings().equals(settings)) {
                continue;
            }
            try {
                registerLocally(name, type, settings, false);
            } catch (RuntimeException e) {
                LOG.warn("[snapshots] failed to apply repository [" + name + "] from cluster state: " + e.getMessage());
            }
        }
    }

    private void mutate(String source, java.util.function.UnaryOperator<Metadata> op) {
        try {
            clusterStateManager.submit(source, cs -> cs.builder().metadata(op.apply(cs.getMetadata())).build())
                .get(30, java.util.concurrent.TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            throw new RestApiException(500, cause.getMessage(), cause);
        } catch (java.util.concurrent.TimeoutException e) {
            throw new RestApiException(503, "timed out waiting for cluster state update [" + source + "]");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RestApiException(500, "interrupted");
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

    private void registerLocally(String name, String type, Map<String, Object> settings, boolean verify) {
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
    }

    public void register(String name, String type, Map<String, Object> settings, boolean verify, boolean persist) {
        registerLocally(name, type, settings, verify);
        if (persist) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("type", type);
            body.put("settings", settings == null ? Map.of() : settings);
            mutate("put-repository [" + name + "]", md -> md.toBuilder().mutateMapCustom(REPOSITORIES_CUSTOM, mc -> mc.with(name, body)).build());
        }
    }

    public void deleteRepository(String name) {
        if (repositories.remove(name) == null) {
            throw new RestApiException(404, "[" + name + "] missing");
        }
        mutate("delete-repository [" + name + "]", md -> md.toBuilder().mutateMapCustom(REPOSITORIES_CUSTOM, mc -> mc.without(name)).build());
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
        List<Releasable> commitRefs = new ArrayList<>();
        try {
            for (String index : indices) {
                IndexMetadata imd = state.getMetadata().index(index);
                IndexService service = indicesService.indexService(index);
                int numberOfShards = imd == null ? (service == null ? 0 : service.shards().size()) : imd.getNumberOfShards();
                for (int shardNum = 0; shardNum < numberOfShards; shardNum++) {
                    IndexShard localShard = service == null ? null : service.shard(shardNum);
                    if (localShard != null) {
                        try {
                            localShard.flush(true);
                        } catch (IOException ex) {
                            throw new RestApiException(500, "failed to flush " + index + "[" + shardNum + "]: " + ex.getMessage(), ex);
                        }
                        commitRefs.add(localShard.acquireLastCommitRef());
                        sources.put(new ShardId(index, shardNum), new DirectoryShardSnapshotSource(service.shardPath(shardNum).resolve("index")));
                        continue;
                    }
                    DiscoveryNode owner = nodeHoldingShard(state, index, shardNum);
                    if (owner == null || transportService == null) {
                        throw new RestApiException(500, "index [" + index + "] shard [" + shardNum
                            + "] has no available copy on this or any other node to snapshot");
                    }
                    sources.put(new ShardId(index, shardNum), new RemoteShardSnapshotSource(owner, index, shardNum));
                }
            }
            try {
                return entry.repository().createSnapshot(new CreateSnapshotRequest(snapshotName, indices, sources,
                    new ClusterMetadataSource(state, indices), null, null, Instant.now()));
            } catch (IOException e) {
                throw new RestApiException(500, "snapshot [" + repositoryName + ":" + snapshotName + "] failed: " + e.getMessage(), e);
            }
        } finally {
            for (Releasable commitRef : commitRefs) {
                commitRef.close();
            }
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
        Map<ShardId, ShardRestoreTarget> metadataOnlyTargets = new LinkedHashMap<>();
        for (String key : info.shardManifestBlobs().keySet()) {
            ShardId shardId = ShardId.parseKey(key);
            String target = targetNames.get(shardId.index());
            if (target == null) {
                continue;
            }
            metadataOnlyTargets.put(new ShardId(target, shardId.shard()), name -> OutputStream.nullOutputStream());
        }
        RestoreResult result;
        try {
            result = entry.repository().restoreSnapshot(snapshotName,
                new RestoreRequest(selected, renamePattern, renameReplacement, overrides, existing), metadataOnlyTargets);
        } catch (IOException | RuntimeException e) {
            throw new RestApiException(500, "restore of [" + repositoryName + ":" + snapshotName + "] failed: " + e.getMessage(), e);
        }
        List<String> restored = new ArrayList<>();
        for (Map.Entry<String, String> e : result.renamedIndices().entrySet()) {
            String source = e.getKey();
            String target = e.getValue();
            Map<String, Object> settings = new LinkedHashMap<>(result.restoredIndexSettings().getOrDefault(target, Map.of()));
            Object mappingsJson = settings.remove(MAPPINGS_SETTING);
            Map<String, Object> mappings = mappingsJson == null ? Map.of()
                : SettingsMaps.asMap(JsonValue.parse(String.valueOf(mappingsJson).getBytes(StandardCharsets.UTF_8)).toJava());
            settings.keySet().removeIf(k -> k.equals("index.uuid") || k.equals("index.creation_date") || k.equals("index.provided_name"));
            settings.put(IndicesService.RECOVERY_TYPE_SETTING, IndicesService.RECOVERY_TYPE_SNAPSHOT);
            settings.put(IndicesService.RECOVERY_REPOSITORY_SETTING, repositoryName);
            settings.put(IndicesService.RECOVERY_SNAPSHOT_SETTING, snapshotName);
            settings.put(IndicesService.RECOVERY_SOURCE_INDEX_SETTING, source);
            String uuid = MetadataIndexService.newUuid();
            indexAdmin.createIndexInternal(target, settings, mappings, Map.of(), uuid, true);
            restored.add(target);
        }
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("snapshot", snapshotName);
        snapshot.put("indices", restored);
        snapshot.put("shards", Map.of("total", metadataOnlyTargets.size(), "failed", 0, "successful", metadataOnlyTargets.size()));
        return Map.of("snapshot", snapshot);
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

    /**
     * A {@link ShardSnapshotSource} for a shard whose only available copy lives on a remote node.
     * Segment file listings and bytes are fetched on demand over the transport layer so the
     * coordinating node's repository code can snapshot shards it does not hold locally.
     */
    private final class RemoteShardSnapshotSource implements ShardSnapshotSource {
        private final DiscoveryNode node;
        private final String index;
        private final int shard;
        private final Map<String, byte[]> cache = new ConcurrentHashMap<>();
        private final Map<String, String> checksums = new ConcurrentHashMap<>();
        private List<String> files;

        RemoteShardSnapshotSource(DiscoveryNode node, String index, int shard) {
            this.node = node;
            this.index = index;
            this.shard = shard;
        }

        @Override
        public synchronized List<String> listSegmentFiles() {
            if (files == null) {
                try {
                    Map<String, Object> response = Wire.decode(Wire.sendSync(transportService, connections.get(node), SHARD_LIST_ACTION,
                        Wire.encode(Map.of("index", index, "shard", shard)), 30_000L));
                    files = SettingsMaps.asStringList(response.get("files"));
                } catch (Exception e) {
                    throw new RestApiException(500, "failed to list shard files on [" + node.getName() + "]: " + e.getMessage(), e);
                }
            }
            return files;
        }

        private byte[] fetch(String name) {
            return cache.computeIfAbsent(name, n -> {
                try {
                    Map<String, Object> response = Wire.decode(Wire.sendSync(transportService, connections.get(node), SHARD_FILE_ACTION,
                        Wire.encode(Map.of("index", index, "shard", shard, "name", n)), 60_000L));
                    return (byte[]) response.get("data");
                } catch (Exception e) {
                    throw new RestApiException(500, "failed to fetch shard file [" + n + "] from [" + node.getName() + "]: "
                        + e.getMessage(), e);
                }
            });
        }

        @Override
        public InputStream openFile(String name) {
            return new java.io.ByteArrayInputStream(fetch(name));
        }

        @Override
        public long fileLength(String name) {
            return fetch(name).length;
        }

        @Override
        public String fileChecksum(String name) {
            return checksums.computeIfAbsent(name, n -> {
                CRC32 crc = new CRC32();
                byte[] data = fetch(n);
                crc.update(data, 0, data.length);
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
