package com.naqqa.elasticsearch.node.indices;

import com.naqqa.elasticsearch.analysis.registry.AnalysisRegistry;
import com.naqqa.elasticsearch.cluster.routing.IndexRoutingTable;
import com.naqqa.elasticsearch.cluster.routing.IndexShardRoutingTable;
import com.naqqa.elasticsearch.cluster.routing.RoutingTable;
import com.naqqa.elasticsearch.cluster.routing.ShardId;
import com.naqqa.elasticsearch.cluster.routing.ShardRouting;
import com.naqqa.elasticsearch.cluster.routing.UnassignedInfo;
import com.naqqa.elasticsearch.cluster.state.AliasMetadata;
import com.naqqa.elasticsearch.cluster.state.ClusterBlocks;
import com.naqqa.elasticsearch.cluster.state.ClusterState;
import com.naqqa.elasticsearch.cluster.state.IndexMetadata;
import com.naqqa.elasticsearch.cluster.state.Metadata;
import com.naqqa.elasticsearch.cluster.state.Settings;
import com.naqqa.elasticsearch.cluster.node.NodeIdentity;
import com.naqqa.elasticsearch.index.mapper.MapperService;
import com.naqqa.elasticsearch.indices.IndexBlocks;
import com.naqqa.elasticsearch.indices.alias.AliasAction;
import com.naqqa.elasticsearch.indices.alias.AliasService;
import com.naqqa.elasticsearch.node.cluster.ClusterStateManager;
import com.naqqa.elasticsearch.node.support.SettingsMaps;
import com.naqqa.elasticsearch.rest.support.IndexNotFoundException;
import com.naqqa.elasticsearch.rest.support.ResourceAlreadyExistsException;
import com.naqqa.elasticsearch.rest.support.RestApiException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

public final class MetadataIndexService {

    public record CreateSpec(String index, String uuid, Map<String, String> settings, Map<String, Object> mappings,
                             Map<String, com.naqqa.elasticsearch.indices.alias.AliasMetadata> aliases) {
    }

    private static final Set<String> NON_DYNAMIC = Set.of("index.number_of_shards", "index.uuid", "index.creation_date",
        "index.provided_name", "index.number_of_routing_shards");

    private final ClusterStateManager clusterStateManager;
    private final IndicesService indicesService;
    private final AnalysisRegistry analysisRegistry;
    private final AliasService aliasService;
    private final int defaultShards;
    private final int defaultReplicas;
    private final long ackTimeoutMillis;

    public MetadataIndexService(ClusterStateManager clusterStateManager, IndicesService indicesService,
                                AnalysisRegistry analysisRegistry, AliasService aliasService, int defaultShards,
                                int defaultReplicas, long ackTimeoutMillis) {
        this.clusterStateManager = clusterStateManager;
        this.indicesService = indicesService;
        this.analysisRegistry = analysisRegistry;
        this.aliasService = aliasService;
        this.defaultShards = defaultShards;
        this.defaultReplicas = defaultReplicas;
        this.ackTimeoutMillis = ackTimeoutMillis;
    }

    public AliasService aliasService() {
        return aliasService;
    }

    public ClusterState state() {
        return clusterStateManager.state();
    }

    public ClusterState await(String source, java.util.function.UnaryOperator<ClusterState> update) {
        try {
            return clusterStateManager.submit(source, update).get(ackTimeoutMillis, TimeUnit.MILLISECONDS);
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

    public static void validateIndexName(String index) {
        if (index == null || index.isEmpty()) {
            throw new RestApiException(400, "index name must not be empty");
        }
        if (!index.toLowerCase(Locale.ROOT).equals(index)) {
            throw new RestApiException(400, "Invalid index name [" + index + "], must be lowercase");
        }
        if (index.startsWith("_") || index.startsWith("-") || index.startsWith("+")) {
            throw new RestApiException(400, "Invalid index name [" + index + "], must not start with '_', '-', or '+'");
        }
        if (".".equals(index) || "..".equals(index)) {
            throw new RestApiException(400, "Invalid index name [" + index + "], must not be '.' or '..'");
        }
        for (char c : new char[] {'\\', '/', '*', '?', '"', '<', '>', '|', ' ', ',', '#', ':'}) {
            if (index.indexOf(c) >= 0) {
                throw new RestApiException(400, "Invalid index name [" + index + "], must not contain '" + c + "'");
            }
        }
    }

    public Map<String, String> defaultedSettings(Map<String, String> flat, String index, String uuid) {
        Map<String, String> out = new LinkedHashMap<>();
        out.put("index.number_of_shards", Integer.toString(defaultShards));
        out.put("index.number_of_replicas", Integer.toString(defaultReplicas));
        out.putAll(flat);
        out.put("index.uuid", uuid);
        out.put("index.provided_name", index);
        out.putIfAbsent("index.creation_date", Long.toString(System.currentTimeMillis()));
        int shards;
        try {
            shards = Integer.parseInt(out.get("index.number_of_shards"));
        } catch (NumberFormatException e) {
            throw new RestApiException(400, "Failed to parse value [" + out.get("index.number_of_shards")
                + "] for setting [index.number_of_shards]");
        }
        if (shards < 1 || shards > 1024) {
            throw new RestApiException(400, "Failed to parse value [" + shards + "] for setting [index.number_of_shards] must be >= 1");
        }
        try {
            if (Integer.parseInt(out.get("index.number_of_replicas")) < 0) {
                throw new NumberFormatException();
            }
        } catch (NumberFormatException e) {
            throw new RestApiException(400, "Failed to parse value [" + out.get("index.number_of_replicas")
                + "] for setting [index.number_of_replicas] must be >= 0");
        }
        return out;
    }

    public void validateMappings(String index, Map<String, String> settings, Map<String, Object> existing, Map<String, Object> update) {
        try {
            Map<String, Object> nested = SettingsMaps.unflatten(settings);
            com.naqqa.elasticsearch.common.settings.Settings.Builder b = com.naqqa.elasticsearch.common.settings.Settings.builder();
            settings.forEach(b::put);
            MapperService probe = new MapperService(analysisRegistry.build(nested), b.build(), index);
            probe.putMapping(existing == null ? Map.of() : existing);
            if (update != null) {
                probe.putMapping(update);
            }
        } catch (RestApiException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new RestApiException(400, "mapper_parsing_exception: " + e.getMessage(), e);
        }
    }

    public Map<String, Object> mergedMapping(String index, Map<String, String> settings, Map<String, Object> existing,
                                             Map<String, Object> update) {
        Map<String, Object> nested = SettingsMaps.unflatten(settings);
        com.naqqa.elasticsearch.common.settings.Settings.Builder b = com.naqqa.elasticsearch.common.settings.Settings.builder();
        settings.forEach(b::put);
        MapperService probe = new MapperService(analysisRegistry.build(nested), b.build(), index);
        try {
            probe.putMapping(existing == null ? Map.of() : existing);
            if (update != null) {
                probe.putMapping(update);
            }
        } catch (RuntimeException e) {
            throw new RestApiException(400, "mapper_parsing_exception: " + e.getMessage(), e);
        }
        Object rendered = probe.documentMapper().mapping().toMapping().toJava();
        Map<String, Object> map = SettingsMaps.asMap(rendered);
        return map == null ? new LinkedHashMap<>() : map;
    }

    public IndexMetadata buildIndexMetadata(CreateSpec spec) {
        Settings.Builder sb = Settings.builder();
        sb.putAll(spec.settings());
        Settings settings = sb.build();
        Map<String, AliasMetadata> aliases = new LinkedHashMap<>();
        for (com.naqqa.elasticsearch.indices.alias.AliasMetadata a : spec.aliases().values()) {
            aliases.put(a.getAlias(), new AliasMetadata(a.getAlias(), a.getIndexRouting(), a.getSearchRouting(),
                a.isWriteIndexExplicit() && a.isWriteIndex()));
        }
        Map<Integer, Long> primaryTerms = new LinkedHashMap<>();
        int shards = settings.getAsInt("index.number_of_shards", 1);
        for (int i = 0; i < shards; i++) {
            primaryTerms.put(i, 1L);
        }
        return new IndexMetadata(spec.index(), spec.uuid(), 1L, IndexMetadata.State.OPEN, settings,
            spec.mappings() == null ? Map.of() : spec.mappings(), aliases, Map.of(), primaryTerms);
    }

    public static IndexRoutingTable unassignedRouting(IndexMetadata imd, UnassignedInfo.Reason reason) {
        IndexRoutingTable.Builder builder = IndexRoutingTable.builder(imd.getIndex());
        long now = System.currentTimeMillis();
        for (int shard = 0; shard < imd.getNumberOfShards(); shard++) {
            List<ShardRouting> copies = new ArrayList<>();
            copies.add(ShardRouting.unassigned(imd.getIndex(), shard, true, UnassignedInfo.of(reason, "index created", now)));
            for (int r = 0; r < imd.getNumberOfReplicas(); r++) {
                copies.add(ShardRouting.unassigned(imd.getIndex(), shard, false, UnassignedInfo.of(reason, "index created", now)));
            }
            builder.putShardTable(new IndexShardRoutingTable(new ShardId(imd.getIndex(), shard), copies));
        }
        return builder.build();
    }

    public IndexMetadata createIndex(CreateSpec spec, long waitMillis) {
        validateIndexName(spec.index());
        IndexMetadata imd = buildIndexMetadata(spec);
        validateMappings(spec.index(), spec.settings(), spec.mappings(), null);
        await("create-index [" + spec.index() + "]", current -> {
            if (current.getMetadata().index(spec.index()) != null) {
                IndexMetadata existing = current.getMetadata().index(spec.index());
                throw new ResourceAlreadyExistsException("index [" + spec.index() + "/" + existing.getIndexUUID() + "] already exists");
            }
            if (!current.getMetadata().resolveIndicesForAlias(spec.index()).isEmpty()) {
                throw new RestApiException(400, "Invalid index name [" + spec.index() + "], already exists as alias");
            }
            for (String alias : imd.getAliases().keySet()) {
                if (current.getMetadata().index(alias) != null || alias.equals(spec.index())) {
                    throw new RestApiException(400, "Invalid alias name [" + alias + "]: an index or data stream exists with the same name as the alias");
                }
            }
            Metadata metadata = current.getMetadata().toBuilder().put(imd).incrementVersion().build();
            RoutingTable routing = current.getRoutingTable().toBuilder().add(unassignedRouting(imd, UnassignedInfo.Reason.INDEX_CREATED)).build();
            ClusterState withIndex = current.builder().metadata(metadata).routingTable(routing).build();
            return clusterStateManager.allocationService().reroute(withIndex, "index [" + spec.index() + "] created",
                System.currentTimeMillis());
        });
        aliasService.registerIndex(spec.index());
        if (!spec.aliases().isEmpty()) {
            List<AliasAction> actions = new ArrayList<>();
            for (com.naqqa.elasticsearch.indices.alias.AliasMetadata a : spec.aliases().values()) {
                AliasAction.Builder ab = AliasAction.add().index(spec.index()).alias(a.getAlias());
                if (a.hasFilter()) {
                    ab.filter(a.getFilter());
                }
                ab.indexRouting(a.getIndexRouting()).searchRouting(a.getSearchRouting());
                if (a.isWriteIndexExplicit()) {
                    ab.writeIndex(a.isWriteIndex());
                }
                actions.add(ab.build());
            }
            aliasService.applyActions(actions);
        }
        if (waitMillis > 0) {
            try {
                indicesService.awaitShardsStarted(spec.index(), waitMillis).get(waitMillis + 1000L, TimeUnit.MILLISECONDS);
            } catch (Exception ignored) {
            }
        }
        return clusterStateManager.state().getMetadata().index(spec.index());
    }

    public boolean primariesActive(String index) {
        ClusterState state = clusterStateManager.state();
        IndexRoutingTable irt = state.getRoutingTable().index(index);
        if (irt == null) {
            return false;
        }
        for (IndexShardRoutingTable table : irt.getShards().values()) {
            ShardRouting primary = table.primaryShard();
            if (primary == null || !primary.active()) {
                return false;
            }
        }
        return true;
    }

    public void deleteIndices(List<String> indices) {
        if (indices.isEmpty()) {
            return;
        }
        await("delete-index " + indices, current -> {
            Metadata.Builder mb = current.getMetadata().toBuilder();
            RoutingTable.Builder rb = current.getRoutingTable().toBuilder();
            ClusterBlocks.Builder bb = current.getBlocks().toBuilder();
            for (String index : indices) {
                if (current.getMetadata().index(index) == null) {
                    throw new IndexNotFoundException(index);
                }
                mb.remove(index);
                rb.remove(index);
                bb.removeIndexBlocks(index);
            }
            return current.builder().metadata(mb.incrementVersion().build()).routingTable(rb.incrementVersion().build())
                .blocks(bb.build()).build();
        });
        for (String index : indices) {
            aliasService.unregisterIndex(index);
        }
    }

    public void setState(List<String> indices, IndexMetadata.State newState) {
        await("index-state " + newState + " " + indices, current -> {
            Metadata.Builder mb = current.getMetadata().toBuilder();
            RoutingTable.Builder rb = current.getRoutingTable().toBuilder();
            ClusterBlocks blocks = current.getBlocks();
            boolean changed = false;
            for (String index : indices) {
                IndexMetadata imd = current.getMetadata().index(index);
                if (imd == null) {
                    throw new IndexNotFoundException(index);
                }
                if (imd.getState() == newState) {
                    continue;
                }
                changed = true;
                mb.put(imd.builder().state(newState).incrementVersion().build());
                if (newState == IndexMetadata.State.CLOSE) {
                    blocks = IndexBlocks.addBlock(blocks, index, IndexBlocks.INDEX_CLOSED);
                } else {
                    blocks = IndexBlocks.removeBlock(blocks, index, IndexBlocks.INDEX_CLOSED);
                    if (current.getRoutingTable().index(index) == null) {
                        rb.add(unassignedRouting(imd, UnassignedInfo.Reason.CLUSTER_RECOVERED));
                    }
                }
            }
            if (!changed) {
                return current;
            }
            ClusterState next = current.builder().metadata(mb.incrementVersion().build()).routingTable(rb.build()).blocks(blocks).build();
            return newState == IndexMetadata.State.OPEN
                ? clusterStateManager.allocationService().reroute(next, "index opened", System.currentTimeMillis()) : next;
        });
        if (newState == IndexMetadata.State.OPEN) {
            for (String index : indices) {
                try {
                    indicesService.awaitShardsStarted(index, 30_000L).get(31, TimeUnit.SECONDS);
                } catch (Exception ignored) {
                }
            }
        }
    }

    public void putMapping(List<String> indices, Map<String, Object> mapping) {
        Map<String, Map<String, Object>> merged = new LinkedHashMap<>();
        ClusterState snapshot = clusterStateManager.state();
        for (String index : indices) {
            IndexMetadata imd = snapshot.getMetadata().index(index);
            if (imd == null) {
                throw new IndexNotFoundException(index);
            }
            IndexBlocks.checkBlockedBeforeMetadataChange(snapshot.getBlocks(), index);
            IndexService service = indicesService.indexService(index);
            Map<String, Object> existing = imd.getMappings();
            if (service != null && service.mapperService().documentMapper() != null) {
                Map<String, Object> live = SettingsMaps.asMap(service.mapperService().documentMapper().mapping().toMapping().toJava());
                if (live != null) {
                    existing = live;
                }
            }
            merged.put(index, mergedMapping(index, imd.getSettings().getAsMap(), existing, mapping));
        }
        await("put-mapping " + indices, current -> {
            Metadata.Builder mb = current.getMetadata().toBuilder();
            for (Map.Entry<String, Map<String, Object>> e : merged.entrySet()) {
                IndexMetadata imd = current.getMetadata().index(e.getKey());
                if (imd == null) {
                    throw new IndexNotFoundException(e.getKey());
                }
                mb.put(imd.builder().mappings(e.getValue()).incrementVersion().build());
            }
            return current.builder().metadata(mb.incrementVersion().build()).build();
        });
    }

    public void putSettings(List<String> indices, Map<String, String> flat) {
        for (String key : flat.keySet()) {
            if (NON_DYNAMIC.contains(key)) {
                IndexMetadata any = indices.isEmpty() ? null : clusterStateManager.state().getMetadata().index(indices.get(0));
                if (any == null || !flat.get(key).equals(any.getSettings().get(key))) {
                    throw new RestApiException(400, "final " + (indices.isEmpty() ? "" : indices.get(0)) + " setting [" + key
                        + "], not updateable");
                }
            }
        }
        await("update-settings " + indices, current -> {
            Metadata.Builder mb = current.getMetadata().toBuilder();
            ClusterBlocks blocks = current.getBlocks();
            RoutingTable.Builder rb = current.getRoutingTable().toBuilder();
            boolean routingChanged = false;
            for (String index : indices) {
                IndexMetadata imd = current.getMetadata().index(index);
                if (imd == null) {
                    throw new IndexNotFoundException(index);
                }
                Map<String, String> merged = new LinkedHashMap<>(imd.getSettings().getAsMap());
                for (Map.Entry<String, String> e : flat.entrySet()) {
                    if (e.getValue() == null || "null".equals(e.getValue())) {
                        merged.remove(e.getKey());
                    } else {
                        merged.put(e.getKey(), e.getValue());
                    }
                }
                IndexMetadata updated = imd.builder().settings(Settings.builder().putAll(merged).build()).incrementVersion().build();
                mb.put(updated);
                blocks = applyBlockSetting(blocks, index, merged, "index.blocks.write", IndexBlocks.INDEX_WRITE);
                blocks = applyBlockSetting(blocks, index, merged, "index.blocks.read_only", IndexBlocks.INDEX_READ_ONLY);
                blocks = applyBlockSetting(blocks, index, merged, "index.blocks.read_only_allow_delete", IndexBlocks.INDEX_READ_ONLY_ALLOW_DELETE);
                blocks = applyBlockSetting(blocks, index, merged, "index.blocks.metadata", IndexBlocks.INDEX_METADATA);
                if (updated.getNumberOfReplicas() != imd.getNumberOfReplicas()) {
                    IndexRoutingTable irt = current.getRoutingTable().index(index);
                    if (irt != null) {
                        rb.add(resizeReplicas(irt, updated.getNumberOfReplicas()));
                        routingChanged = true;
                    }
                }
            }
            ClusterState next = current.builder().metadata(mb.incrementVersion().build()).blocks(blocks)
                .routingTable(rb.build()).build();
            return routingChanged ? clusterStateManager.allocationService().reroute(next, "replicas updated", System.currentTimeMillis()) : next;
        });
    }

    private static IndexRoutingTable resizeReplicas(IndexRoutingTable irt, int replicas) {
        IndexRoutingTable.Builder builder = IndexRoutingTable.builder(irt.getIndex());
        long now = System.currentTimeMillis();
        for (IndexShardRoutingTable table : irt.getShards().values()) {
            List<ShardRouting> copies = new ArrayList<>();
            ShardRouting primary = table.primaryShard();
            if (primary != null) {
                copies.add(primary);
            }
            List<ShardRouting> replicaCopies = new ArrayList<>(table.replicaShards());
            replicaCopies.sort((a, b) -> Boolean.compare(b.active(), a.active()));
            for (int i = 0; i < replicas; i++) {
                if (i < replicaCopies.size()) {
                    copies.add(replicaCopies.get(i));
                } else {
                    copies.add(ShardRouting.unassigned(irt.getIndex(), table.getShardId().id(), false,
                        UnassignedInfo.of(UnassignedInfo.Reason.REPLICA_ADDED, "replica added", now)));
                }
            }
            builder.putShardTable(new IndexShardRoutingTable(table.getShardId(), copies));
        }
        return builder.build();
    }

    private static ClusterBlocks applyBlockSetting(ClusterBlocks blocks, String index, Map<String, String> settings, String key,
                                                   com.naqqa.elasticsearch.cluster.state.ClusterBlock block) {
        boolean enabled = "true".equalsIgnoreCase(settings.get(key));
        return enabled ? IndexBlocks.addBlock(blocks, index, block) : IndexBlocks.removeBlock(blocks, index, block);
    }

    public void applyAliasActions(List<AliasAction> actions) {
        ClusterState state = clusterStateManager.state();
        for (AliasAction action : actions) {
            if (action.getIndex() != null && state.getMetadata().index(action.getIndex()) == null) {
                throw new IndexNotFoundException(action.getIndex());
            }
            if (action.getType() == AliasAction.Type.ADD && state.getMetadata().index(action.getAlias()) != null) {
                throw new RestApiException(400, "Invalid alias name [" + action.getAlias()
                    + "]: an index or data stream exists with the same name as the alias");
            }
            aliasService.registerIndex(action.getIndex());
        }
        try {
            aliasService.applyActions(actions);
        } catch (RestApiException e) {
            throw e;
        } catch (IllegalArgumentException | IllegalStateException e) {
            String msg = String.valueOf(e.getMessage());
            throw new RestApiException(msg.contains("missing") || msg.contains("not found") ? 404 : 400, msg, e);
        }
        List<String> removedIndices = new ArrayList<>();
        for (AliasAction action : actions) {
            if (action.getType() == AliasAction.Type.REMOVE_INDEX) {
                removedIndices.add(action.getIndex());
            }
        }
        syncAliasesToClusterState();
        if (!removedIndices.isEmpty()) {
            deleteIndices(removedIndices);
        }
    }

    public void syncAliasesToClusterState() {
        await("update-aliases", current -> {
            Metadata.Builder mb = current.getMetadata().toBuilder();
            boolean changed = false;
            for (IndexMetadata imd : current.getMetadata().getIndices().values()) {
                Map<String, AliasMetadata> desired = new LinkedHashMap<>();
                for (com.naqqa.elasticsearch.indices.alias.AliasMetadata a : aliasService.getAliases(imd.getIndex()).values()) {
                    desired.put(a.getAlias(), new AliasMetadata(a.getAlias(), a.getIndexRouting(), a.getSearchRouting(),
                        a.isWriteIndexExplicit() && a.isWriteIndex()));
                }
                if (!sameAliases(desired, imd.getAliases())) {
                    changed = true;
                    mb.put(new IndexMetadata(imd.getIndex(), imd.getIndexUUID(), imd.getVersion() + 1, imd.getState(),
                        imd.getSettings(), imd.getMappings(), desired, imd.getInSyncAllocationIds(), imd.getPrimaryTerms()));
                }
            }
            return changed ? current.builder().metadata(mb.incrementVersion().build()).build() : current;
        });
    }

    private static boolean sameAliases(Map<String, AliasMetadata> a, Map<String, AliasMetadata> b) {
        if (!a.keySet().equals(b.keySet())) {
            return false;
        }
        for (Map.Entry<String, AliasMetadata> e : a.entrySet()) {
            AliasMetadata other = b.get(e.getKey());
            AliasMetadata mine = e.getValue();
            if (!java.util.Objects.equals(mine.getIndexRouting(), other.getIndexRouting())
                || !java.util.Objects.equals(mine.getSearchRouting(), other.getSearchRouting())
                || mine.isWriteIndex() != other.isWriteIndex()) {
                return false;
            }
        }
        return true;
    }

    public void restoreAliasesFromClusterState() {
        List<AliasAction> actions = new ArrayList<>();
        for (IndexMetadata imd : clusterStateManager.state().getMetadata().getIndices().values()) {
            aliasService.registerIndex(imd.getIndex());
            for (AliasMetadata a : imd.getAliases().values()) {
                AliasAction.Builder b = AliasAction.add().index(imd.getIndex()).alias(a.getAlias())
                    .indexRouting(a.getIndexRouting()).searchRouting(a.getSearchRouting());
                if (a.isWriteIndex()) {
                    b.writeIndex(true);
                }
                actions.add(b.build());
            }
        }
        if (!actions.isEmpty()) {
            try {
                aliasService.applyActions(actions);
            } catch (RuntimeException e) {
                System.err.println("[indices] failed to restore aliases: " + e);
            }
        }
    }

    public static String newUuid() {
        return NodeIdentity.generate();
    }

    public static RuntimeException unwrap(Throwable t) {
        Throwable cause = t instanceof CompletionException && t.getCause() != null ? t.getCause() : t;
        return cause instanceof RuntimeException re ? re : new RestApiException(500, cause.getMessage(), cause);
    }
}
