package com.naqqa.elasticsearch.action.get;

import com.naqqa.elasticsearch.action.search.ShardCopySelector;
import com.naqqa.elasticsearch.cluster.routing.IndexRoutingTable;
import com.naqqa.elasticsearch.cluster.routing.IndexShardRoutingTable;
import com.naqqa.elasticsearch.cluster.routing.RoutingTable;
import com.naqqa.elasticsearch.cluster.routing.ShardId;
import com.naqqa.elasticsearch.cluster.routing.ShardRouting;
import com.naqqa.elasticsearch.common.hash.Murmur3HashFunction;
import com.naqqa.elasticsearch.common.io.stream.Writeable;
import com.naqqa.elasticsearch.transport.Connection;
import com.naqqa.elasticsearch.transport.ConnectionProfile;
import com.naqqa.elasticsearch.transport.DiscoveryNode;
import com.naqqa.elasticsearch.transport.TransportException;
import com.naqqa.elasticsearch.transport.TransportRequest;
import com.naqqa.elasticsearch.transport.TransportRequestOptions;
import com.naqqa.elasticsearch.transport.TransportResponse;
import com.naqqa.elasticsearch.transport.TransportResponseHandler;
import com.naqqa.elasticsearch.transport.TransportService;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

public final class TransportGetAction {

    private static final long DEFAULT_TIMEOUT_MILLIS = 30_000L;

    private record ShardKey(String index, int shardId) {
    }

    private final TransportService transportService;
    private final Map<String, DiscoveryNode> nodes;
    private final ShardCopySelector selector = new ShardCopySelector.RoundRobin();
    private final Map<String, Connection> connections = new ConcurrentHashMap<>();

    public TransportGetAction(TransportService transportService, Map<String, DiscoveryNode> nodes) {
        this.transportService = transportService;
        this.nodes = nodes;
    }

    public static int routeToShard(String id, int numShards) {
        int hash = Murmur3HashFunction.hash(id) & 0x7fffffff;
        return hash % numShards;
    }

    public GetResponse get(RoutingTable routingTable, GetRequest request) {
        IndexRoutingTable indexRouting = routingTable.index(request.index());
        if (indexRouting == null) {
            return GetResponse.failed(request.index(), request.id(), "no such index [" + request.index() + "]");
        }
        int numShards = indexRouting.getShards().size();
        int shardNum = routeToShard(request.id(), numShards);
        ShardId shardId = new ShardId(request.index(), shardNum);
        List<ShardRouting> candidates = startedCopies(indexRouting.shard(shardNum));
        ShardRouting chosen = selector.select(shardId, candidates);
        if (chosen == null) {
            return GetResponse.failed(request.index(), request.id(), "no active shard copies available");
        }
        try {
            ShardGetService.ShardGetResponse resp = this.<ShardGetService.ShardGetResponse>sendRequest(
                chosen, ShardGetService.ACTION_GET, new ShardGetService.ShardGetRequest(shardId, request.id()),
                ShardGetService.ShardGetResponse::new).get(DEFAULT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
            return resp.found()
                ? new GetResponse(request.index(), request.id(), true, resp.version(), resp.source(), null)
                : GetResponse.notFound(request.index(), request.id());
        } catch (Exception e) {
            return GetResponse.failed(request.index(), request.id(), describe(e));
        }
    }

    public MultiGetResponse mget(RoutingTable routingTable, MultiGetRequest request) {
        int n = request.items().size();
        GetResponse[] results = new GetResponse[n];
        Map<ShardKey, List<Integer>> grouping = new LinkedHashMap<>();
        Map<ShardKey, IndexShardRoutingTable> tableByKey = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            MultiGetRequest.Item item = request.items().get(i);
            IndexRoutingTable indexRouting = routingTable.index(item.index());
            if (indexRouting == null) {
                results[i] = GetResponse.failed(item.index(), item.id(), "no such index [" + item.index() + "]");
                continue;
            }
            int numShards = indexRouting.getShards().size();
            int shardNum = routeToShard(item.id(), numShards);
            ShardKey key = new ShardKey(item.index(), shardNum);
            grouping.computeIfAbsent(key, k -> new ArrayList<>()).add(i);
            tableByKey.putIfAbsent(key, indexRouting.shard(shardNum));
        }

        Map<ShardKey, ShardRouting> chosenByKey = new LinkedHashMap<>();
        Map<ShardKey, CompletableFuture<ShardGetService.ShardMultiGetResponse>> futures = new LinkedHashMap<>();
        for (Map.Entry<ShardKey, List<Integer>> e : grouping.entrySet()) {
            ShardKey key = e.getKey();
            ShardId shardId = new ShardId(key.index(), key.shardId());
            List<ShardRouting> candidates = startedCopies(tableByKey.get(key));
            ShardRouting chosen = selector.select(shardId, candidates);
            if (chosen == null) {
                continue;
            }
            chosenByKey.put(key, chosen);
            List<String> ids = new ArrayList<>();
            for (int pos : e.getValue()) {
                ids.add(request.items().get(pos).id());
            }
            futures.put(key, this.<ShardGetService.ShardMultiGetResponse>sendRequest(
                chosen, ShardGetService.ACTION_MGET, new ShardGetService.ShardMultiGetRequest(shardId, ids),
                ShardGetService.ShardMultiGetResponse::new));
        }

        for (Map.Entry<ShardKey, List<Integer>> e : grouping.entrySet()) {
            ShardKey key = e.getKey();
            List<Integer> positions = e.getValue();
            if (!chosenByKey.containsKey(key)) {
                for (int pos : positions) {
                    results[pos] = GetResponse.failed(key.index(), request.items().get(pos).id(), "no active shard copies available");
                }
                continue;
            }
            try {
                ShardGetService.ShardMultiGetResponse resp = futures.get(key).get(DEFAULT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
                for (int j = 0; j < positions.size(); j++) {
                    int pos = positions.get(j);
                    ShardGetService.ShardGetResponse r = resp.results().get(j);
                    results[pos] = r.found()
                        ? new GetResponse(key.index(), r.id(), true, r.version(), r.source(), null)
                        : GetResponse.notFound(key.index(), r.id());
                }
            } catch (Exception ex) {
                for (int pos : positions) {
                    results[pos] = GetResponse.failed(key.index(), request.items().get(pos).id(), describe(ex));
                }
            }
        }

        return new MultiGetResponse(Arrays.asList(results));
    }

    private static List<ShardRouting> startedCopies(IndexShardRoutingTable table) {
        List<ShardRouting> result = new ArrayList<>();
        if (table == null) {
            return result;
        }
        for (ShardRouting sr : table.getShards()) {
            if (sr.started()) {
                result.add(sr);
            }
        }
        return result;
    }

    private static String describe(Throwable t) {
        Throwable cause = t instanceof ExecutionException && t.getCause() != null ? t.getCause() : t;
        return cause.getClass().getSimpleName() + ": " + cause.getMessage();
    }

    private <T extends TransportResponse> CompletableFuture<T> sendRequest(
        ShardRouting copy, String action, TransportRequest request, Writeable.Reader<T> reader) {
        CompletableFuture<T> future = new CompletableFuture<>();
        try {
            Connection connection = connectionFor(copy.currentNodeId());
            transportService.sendRequest(connection, action, request,
                TransportRequestOptions.of().withTimeout(DEFAULT_TIMEOUT_MILLIS),
                new TransportResponseHandler<T>() {
                    @Override
                    public void handleResponse(T response) {
                        future.complete(response);
                    }

                    @Override
                    public void handleException(TransportException exp) {
                        future.completeExceptionally(exp);
                    }

                    @Override
                    public Writeable.Reader<T> reader() {
                        return reader;
                    }
                });
        } catch (Exception e) {
            future.completeExceptionally(e);
        }
        return future;
    }

    private Connection connectionFor(String nodeId) {
        return connections.computeIfAbsent(nodeId, id -> {
            DiscoveryNode node = nodes.get(id);
            if (node == null) {
                throw new IllegalStateException("unknown node [" + id + "]");
            }
            return transportService.connectToNode(node, ConnectionProfile.buildDefault());
        });
    }
}
