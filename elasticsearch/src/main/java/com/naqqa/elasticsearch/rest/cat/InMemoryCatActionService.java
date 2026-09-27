package com.naqqa.elasticsearch.rest.cat;

import com.naqqa.elasticsearch.rest.document.InMemoryDocumentActionService;
import com.naqqa.elasticsearch.rest.indices.InMemoryIndexAdminActionService;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public final class InMemoryCatActionService implements CatActionService {

    private final InMemoryIndexAdminActionService indexAdmin;
    private final InMemoryDocumentActionService documents;
    private final String clusterName;
    private final String nodeName;

    public InMemoryCatActionService(InMemoryIndexAdminActionService indexAdmin, InMemoryDocumentActionService documents,
                                     String clusterName, String nodeName) {
        this.indexAdmin = indexAdmin;
        this.documents = documents;
        this.clusterName = clusterName;
        this.nodeName = nodeName;
    }

    private static Map<String, String> row(String... kv) {
        Map<String, String> row = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            row.put(kv[i], kv[i + 1]);
        }
        return row;
    }

    private long docCount(String index) {
        Map<String, InMemoryDocumentActionService.StoredDoc> store = documents.rawIndices().get(index);
        if (store == null) {
            return 0;
        }
        return store.values().stream().filter(d -> !d.deleted).count();
    }

    private long deletedCount(String index) {
        Map<String, InMemoryDocumentActionService.StoredDoc> store = documents.rawIndices().get(index);
        if (store == null) {
            return 0;
        }
        return store.values().stream().filter(d -> d.deleted).count();
    }

    @Override
    public CompletableFuture<CatTable> indices(Map<String, String> params) {
        List<String> columns = List.of("health", "status", "index", "uuid", "pri", "rep", "docs.count",
            "docs.deleted", "store.size", "pri.store.size");
        List<Map<String, String>> rows = new ArrayList<>();
        for (Map.Entry<String, InMemoryIndexAdminActionService.IndexMeta> entry : indexAdmin.rawIndices().entrySet()) {
            String name = entry.getKey();
            InMemoryIndexAdminActionService.IndexMeta meta = entry.getValue();
            rows.add(row("health", "green", "status", meta.open ? "open" : "close", "index", name,
                "uuid", Integer.toHexString(name.hashCode()), "pri", "1", "rep", "1",
                "docs.count", String.valueOf(docCount(name)), "docs.deleted", String.valueOf(deletedCount(name)),
                "store.size", "0", "pri.store.size", "0"));
        }
        return CompletableFuture.completedFuture(new CatTable(columns, rows));
    }

    @Override
    public CompletableFuture<CatTable> shards(Map<String, String> params) {
        List<String> columns = List.of("index", "shard", "prirep", "state", "docs", "store", "ip", "node");
        List<Map<String, String>> rows = new ArrayList<>();
        for (String name : indexAdmin.rawIndices().keySet()) {
            rows.add(row("index", name, "shard", "0", "prirep", "p", "state", "STARTED",
                "docs", String.valueOf(docCount(name)), "store", "0", "ip", "127.0.0.1", "node", nodeName));
        }
        return CompletableFuture.completedFuture(new CatTable(columns, rows));
    }

    @Override
    public CompletableFuture<CatTable> nodes(Map<String, String> params) {
        List<String> columns = List.of("ip", "heap.percent", "ram.percent", "cpu", "load_1m", "node.role", "master", "name");
        List<Map<String, String>> rows = List.of(row("ip", "127.0.0.1", "heap.percent", "0", "ram.percent", "0",
            "cpu", "0", "load_1m", "0.0", "node.role", "dim", "master", "*", "name", nodeName));
        return CompletableFuture.completedFuture(new CatTable(columns, rows));
    }

    @Override
    public CompletableFuture<CatTable> health(Map<String, String> params) {
        List<String> columns = List.of("epoch", "timestamp", "cluster", "status", "node.total", "node.data",
            "shards", "pri", "relo", "init", "unassign", "pending_tasks", "max_task_wait_time", "active_shards_percent");
        Instant now = Instant.now();
        String epoch = String.valueOf(now.getEpochSecond());
        String timestamp = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(java.time.ZoneOffset.UTC).format(now);
        List<Map<String, String>> rows = List.of(row("epoch", epoch, "timestamp", timestamp, "cluster", clusterName,
            "status", "green", "node.total", "1", "node.data", "1", "shards", "0", "pri", "0", "relo", "0",
            "init", "0", "unassign", "0", "pending_tasks", "0", "max_task_wait_time", "0", "active_shards_percent", "100.0%"));
        return CompletableFuture.completedFuture(new CatTable(columns, rows));
    }

    @Override
    public CompletableFuture<CatTable> allocation(Map<String, String> params) {
        List<String> columns = List.of("shards", "disk.indices", "disk.used", "disk.avail", "disk.total", "disk.percent", "host", "ip", "node");
        List<Map<String, String>> rows = List.of(row("shards", String.valueOf(indexAdmin.rawIndices().size()),
            "disk.indices", "0", "disk.used", "0", "disk.avail", "0", "disk.total", "0", "disk.percent", "0",
            "host", "127.0.0.1", "ip", "127.0.0.1", "node", nodeName));
        return CompletableFuture.completedFuture(new CatTable(columns, rows));
    }

    @Override
    public CompletableFuture<CatTable> count(Map<String, String> params) {
        List<String> columns = List.of("epoch", "timestamp", "count");
        String index = params.get("index");
        long total = 0;
        if (index != null) {
            total = docCount(index);
        } else {
            for (String name : indexAdmin.rawIndices().keySet()) {
                total += docCount(name);
            }
        }
        Instant now = Instant.now();
        List<Map<String, String>> rows = List.of(row("epoch", String.valueOf(now.getEpochSecond()),
            "timestamp", DateTimeFormatter.ofPattern("HH:mm:ss").withZone(java.time.ZoneOffset.UTC).format(now),
            "count", String.valueOf(total)));
        return CompletableFuture.completedFuture(new CatTable(columns, rows));
    }

    @Override
    public CompletableFuture<CatTable> aliases(Map<String, String> params) {
        List<String> columns = List.of("alias", "index", "filter", "routing.index", "routing.search", "is_write_index");
        List<Map<String, String>> rows = new ArrayList<>();
        for (Map.Entry<String, InMemoryIndexAdminActionService.IndexMeta> entry : indexAdmin.rawIndices().entrySet()) {
            for (Map.Entry<String, Map<String, Object>> aliasEntry : entry.getValue().aliases.entrySet()) {
                Object writeIndex = aliasEntry.getValue().get("is_write_index");
                rows.add(row("alias", aliasEntry.getKey(), "index", entry.getKey(), "filter",
                    aliasEntry.getValue().containsKey("filter") ? "*" : "-", "routing.index", "-", "routing.search", "-",
                    "is_write_index", writeIndex == null ? "-" : String.valueOf(writeIndex)));
            }
        }
        return CompletableFuture.completedFuture(new CatTable(columns, rows));
    }

    @Override
    public CompletableFuture<CatTable> segments(Map<String, String> params) {
        List<String> columns = List.of("index", "shard", "prirep", "ip", "segment", "generation", "docs.count",
            "docs.deleted", "size", "size.memory", "committed", "searchable", "version", "compound");
        List<Map<String, String>> rows = new ArrayList<>();
        for (String name : indexAdmin.rawIndices().keySet()) {
            rows.add(row("index", name, "shard", "0", "prirep", "p", "ip", "127.0.0.1", "segment", "_0",
                "generation", "0", "docs.count", String.valueOf(docCount(name)), "docs.deleted", String.valueOf(deletedCount(name)),
                "size", "0", "size.memory", "0", "committed", "true", "searchable", "true", "version", "1.0", "compound", "true"));
        }
        return CompletableFuture.completedFuture(new CatTable(columns, rows));
    }

    @Override
    public CompletableFuture<CatTable> recovery(Map<String, String> params) {
        List<String> columns = List.of("index", "shard", "time", "type", "stage", "source_host", "source_node",
            "target_host", "target_node", "repository", "snapshot", "files", "files_recovered", "files_percent",
            "files_total", "bytes", "bytes_recovered", "bytes_percent", "bytes_total", "translog_ops",
            "translog_ops_recovered", "translog_ops_percent");
        List<Map<String, String>> rows = new ArrayList<>();
        for (String name : indexAdmin.rawIndices().keySet()) {
            rows.add(row("index", name, "shard", "0", "time", "0", "type", "empty_store", "stage", "done",
                "source_host", "n/a", "source_node", "n/a", "target_host", "127.0.0.1", "target_node", nodeName,
                "repository", "n/a", "snapshot", "n/a", "files", "0", "files_recovered", "0", "files_percent", "100.0%",
                "files_total", "0", "bytes", "0", "bytes_recovered", "0", "bytes_percent", "100.0%", "bytes_total", "0",
                "translog_ops", "0", "translog_ops_recovered", "0", "translog_ops_percent", "100.0%"));
        }
        return CompletableFuture.completedFuture(new CatTable(columns, rows));
    }

    @Override
    public CompletableFuture<CatTable> threadPool(Map<String, String> params) {
        List<String> columns = List.of("node_name", "name", "active", "queue", "rejected", "completed");
        List<Map<String, String>> rows = new ArrayList<>();
        for (String pool : List.of("generic", "search", "write", "get", "management", "refresh", "flush", "force_merge", "snapshot")) {
            rows.add(row("node_name", nodeName, "name", pool, "active", "0", "queue", "0", "rejected", "0", "completed", "0"));
        }
        return CompletableFuture.completedFuture(new CatTable(columns, rows));
    }

    @Override
    public CompletableFuture<CatTable> master(Map<String, String> params) {
        List<String> columns = List.of("id", "host", "ip", "node");
        List<Map<String, String>> rows = List.of(row("id", "node-1", "host", "127.0.0.1", "ip", "127.0.0.1", "node", nodeName));
        return CompletableFuture.completedFuture(new CatTable(columns, rows));
    }

    @Override
    public CompletableFuture<CatTable> plugins(Map<String, String> params) {
        List<String> columns = List.of("name", "component", "version", "description");
        return CompletableFuture.completedFuture(new CatTable(columns, List.of()));
    }

    @Override
    public CompletableFuture<CatTable> templates(Map<String, String> params) {
        List<String> columns = List.of("name", "index_patterns", "order", "version", "composed_of");
        return CompletableFuture.completedFuture(new CatTable(columns, List.of()));
    }

    @Override
    public CompletableFuture<CatTable> fielddata(Map<String, String> params) {
        List<String> columns = List.of("id", "host", "ip", "node", "field", "size");
        return CompletableFuture.completedFuture(new CatTable(columns, List.of()));
    }

    @Override
    public CompletableFuture<CatTable> pendingTasks(Map<String, String> params) {
        List<String> columns = List.of("insertOrder", "timeInQueue", "priority", "source");
        return CompletableFuture.completedFuture(new CatTable(columns, List.of()));
    }

    @Override
    public CompletableFuture<CatTable> tasks(Map<String, String> params) {
        List<String> columns = List.of("id", "action", "task_id", "parent_task_id", "type", "start_time", "timestamp", "running_time", "ip", "node");
        return CompletableFuture.completedFuture(new CatTable(columns, List.of()));
    }

    @Override
    public CompletableFuture<CatTable> repositories(Map<String, String> params) {
        List<String> columns = List.of("id", "type");
        return CompletableFuture.completedFuture(new CatTable(columns, List.of()));
    }

    @Override
    public CompletableFuture<CatTable> snapshots(Map<String, String> params) {
        List<String> columns = List.of("id", "status", "start_epoch", "start_time", "end_epoch", "end_time",
            "duration", "indices", "successful_shards", "failed_shards", "total_shards");
        return CompletableFuture.completedFuture(new CatTable(columns, List.of()));
    }
}
