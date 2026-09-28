package com.naqqa.elasticsearch.node.indices;

import com.naqqa.elasticsearch.cluster.routing.ShardId;
import com.naqqa.elasticsearch.common.threadpool.ThreadPool;
import com.naqqa.elasticsearch.index.recovery.RecoverySourceHandler;
import com.naqqa.elasticsearch.index.recovery.RecoveryState;
import com.naqqa.elasticsearch.index.recovery.RecoveryTarget;
import com.naqqa.elasticsearch.index.recovery.RecoveryThrottler;
import com.naqqa.elasticsearch.index.recovery.RetentionLeaseTracker;
import com.naqqa.elasticsearch.index.recovery.TranslogOpsSource;
import com.naqqa.elasticsearch.index.shard.IndexShard;
import com.naqqa.elasticsearch.index.translog.TranslogConfig;
import com.naqqa.elasticsearch.node.cluster.Wire;
import com.naqqa.elasticsearch.store.FSDirectory;
import com.naqqa.elasticsearch.transport.Connection;
import com.naqqa.elasticsearch.transport.ConnectionProfile;
import com.naqqa.elasticsearch.transport.DiscoveryNode;
import com.naqqa.elasticsearch.transport.TransportService;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

final class PeerRecoveryService implements AutoCloseable {

    static final String PREPARE_ACTION = "internal:index/shard/recovery/prepare";
    static final String RELEASE_ACTION = "internal:index/shard/recovery/release";
    private static final long SESSION_TTL_MILLIS = 15 * 60_000L;

    interface SourceResolver {
        Source prepare(ShardId shardId, String targetAllocationId, String targetNodeId) throws IOException;
    }

    record Source(IndexShard shard, Path shardPath, TranslogConfig translogConfig) {
    }

    private record Session(TransportService transport, FSDirectory directory, long createdAt) {
    }

    private final TransportService transportService;
    private final ThreadPool threadPool;
    private final String localNodeId;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final AtomicLong sessionIds = new AtomicLong();

    PeerRecoveryService(TransportService transportService, ThreadPool threadPool, String localNodeId, SourceResolver resolver) {
        this.transportService = transportService;
        this.threadPool = threadPool;
        this.localNodeId = localNodeId;
        Wire.register(transportService, PREPARE_ACTION, payload -> {
            Map<String, Object> request = Wire.decode(payload);
            ShardId shardId = new ShardId(String.valueOf(request.get("index")), ((Number) request.get("shard")).intValue());
            expireSessions();
            Source source = resolver.prepare(shardId, String.valueOf(request.get("allocation_id")),
                String.valueOf(request.get("target_node")));
            String sessionId = localNodeId + "-" + shardId.index() + "-" + shardId.id() + "-" + sessionIds.incrementAndGet();
            TransportService endpoint = new TransportService(sessionId,
                new InetSocketAddress(transportService.localNode().host(), 0), threadPool);
            FSDirectory directory = null;
            try {
                endpoint.start();
                directory = new FSDirectory(source.shardPath().resolve("index"));
                RecoverySourceHandler handler = new RecoverySourceHandler(source.shard(), directory,
                    new TranslogOpsSource(source.shardPath().resolve("translog"), source.translogConfig()),
                    RecoveryThrottler.unthrottled(), new RetentionLeaseTracker());
                handler.registerHandlers(endpoint);
            } catch (Exception e) {
                closeQuietly(endpoint, directory);
                throw e;
            }
            sessions.put(sessionId, new Session(endpoint, directory, System.currentTimeMillis()));
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("session", sessionId);
            out.put("host", endpoint.localNode().host());
            out.put("port", endpoint.localNode().port());
            return CompletableFuture.completedFuture(Wire.encode(out));
        });
        Wire.register(transportService, RELEASE_ACTION, payload -> {
            release(String.valueOf(Wire.decode(payload).get("session")));
            return CompletableFuture.completedFuture(new byte[0]);
        });
    }

    private void release(String sessionId) {
        Session session = sessions.remove(sessionId);
        if (session != null) {
            closeQuietly(session.transport(), session.directory());
        }
    }

    private void expireSessions() {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, Session> e : sessions.entrySet()) {
            if (now - e.getValue().createdAt() > SESSION_TTL_MILLIS) {
                release(e.getKey());
            }
        }
    }

    private static void closeQuietly(TransportService endpoint, FSDirectory directory) {
        try {
            endpoint.close();
        } catch (RuntimeException ignored) {
        }
        if (directory != null) {
            try {
                directory.close();
            } catch (Exception ignored) {
            }
        }
    }

    IndexShard recover(Connection primaryConnection, ShardId shardId, String allocationId, IndexService service) throws IOException {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("index", shardId.index());
        request.put("shard", shardId.id());
        request.put("allocation_id", allocationId);
        request.put("target_node", localNodeId);
        Map<String, Object> prepared = Wire.decode(Wire.sendSync(transportService, primaryConnection, PREPARE_ACTION,
            Wire.encode(request), 60_000L));
        String sessionId = String.valueOf(prepared.get("session"));
        try {
            service.closeShard(shardId.id());
            Path shardPath = service.shardPath(shardId.id());
            IndicesService.deleteRecursively(shardPath.resolve("translog"));
            Files.createDirectories(shardPath.resolve("index"));
            TranslogConfig translogConfig = service.newTranslogConfig(shardId.id());
            DiscoveryNode source = new DiscoveryNode(sessionId, String.valueOf(prepared.get("host")),
                ((Number) prepared.get("port")).intValue());
            Connection connection = transportService.connectToNode(source, ConnectionProfile.builder()
                .addConnections(ConnectionProfile.ChannelType.REG, 1).addConnections(ConnectionProfile.ChannelType.RECOVERY, 1)
                .build());
            try {
                IndexShard shard = RecoveryTarget.recover(shardPath, service.mapperService(), translogConfig, transportService, connection,
                    new RecoveryState(RecoveryState.Type.PEER, source, transportService.localNode()));
                service.installShard(shardId.id(), shard, translogConfig);
                return shard;
            } finally {
                connection.close();
            }
        } finally {
            Map<String, Object> release = new LinkedHashMap<>();
            release.put("session", sessionId);
            Wire.send(transportService, primaryConnection, RELEASE_ACTION, Wire.encode(release), 10_000L);
        }
    }

    @Override
    public void close() {
        for (String id : sessions.keySet()) {
            release(id);
        }
    }
}
