package com.naqqa.elasticsearch.index.recovery;

import com.naqqa.elasticsearch.transport.DiscoveryNode;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public final class RecoveryState {

    public enum Stage {
        INIT, INDEX, TRANSLOG, DONE
    }

    public enum Type {
        PEER, STORE, SNAPSHOT
    }

    private final Type type;
    private final DiscoveryNode sourceNode;
    private final DiscoveryNode targetNode;

    private volatile Stage stage = Stage.INIT;
    private volatile long startTimeMillis;
    private volatile long stopTimeMillis;

    private final AtomicInteger filesTotal = new AtomicInteger();
    private final AtomicInteger filesRecovered = new AtomicInteger();
    private final AtomicLong bytesTotal = new AtomicLong();
    private final AtomicLong bytesRecovered = new AtomicLong();

    private final AtomicLong translogOpsTotal = new AtomicLong(-1);
    private final AtomicLong translogOpsRecovered = new AtomicLong();

    public RecoveryState(Type type, DiscoveryNode sourceNode, DiscoveryNode targetNode) {
        this.type = type;
        this.sourceNode = sourceNode;
        this.targetNode = targetNode;
    }

    public void start() {
        this.startTimeMillis = System.currentTimeMillis();
        this.stage = Stage.INIT;
    }

    public void setStage(Stage stage) {
        this.stage = stage;
    }

    public void done() {
        this.stage = Stage.DONE;
        this.stopTimeMillis = System.currentTimeMillis();
    }

    public Stage stage() {
        return stage;
    }

    public Type type() {
        return type;
    }

    public DiscoveryNode sourceNode() {
        return sourceNode;
    }

    public DiscoveryNode targetNode() {
        return targetNode;
    }

    public long startTimeMillis() {
        return startTimeMillis;
    }

    public long stopTimeMillis() {
        return stopTimeMillis;
    }

    public void setFilesTotal(int total) {
        filesTotal.set(total);
    }

    public void setBytesTotal(long total) {
        bytesTotal.set(total);
    }

    public void incrementFilesRecovered() {
        filesRecovered.incrementAndGet();
    }

    public void addBytesRecovered(long bytes) {
        bytesRecovered.addAndGet(bytes);
    }

    public int filesTotal() {
        return filesTotal.get();
    }

    public int filesRecovered() {
        return filesRecovered.get();
    }

    public long bytesTotal() {
        return bytesTotal.get();
    }

    public long bytesRecovered() {
        return bytesRecovered.get();
    }

    public void setTranslogOpsTotal(long total) {
        translogOpsTotal.set(total);
    }

    public void addTranslogOpsRecovered(long count) {
        translogOpsRecovered.addAndGet(count);
    }

    public long translogOpsTotal() {
        return translogOpsTotal.get();
    }

    public long translogOpsRecovered() {
        return translogOpsRecovered.get();
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("type", type.name());
        map.put("stage", stage.name().toLowerCase(java.util.Locale.ROOT));
        map.put("source", sourceNode == null ? null : sourceNode.id());
        map.put("target", targetNode == null ? null : targetNode.id());
        map.put("start_time_in_millis", startTimeMillis);
        map.put("stop_time_in_millis", stopTimeMillis);
        Map<String, Object> index = new LinkedHashMap<>();
        index.put("files_total", filesTotal.get());
        index.put("files_recovered", filesRecovered.get());
        index.put("bytes_total", bytesTotal.get());
        index.put("bytes_recovered", bytesRecovered.get());
        map.put("index", index);
        Map<String, Object> translog = new LinkedHashMap<>();
        translog.put("total", translogOpsTotal.get());
        translog.put("recovered", translogOpsRecovered.get());
        map.put("translog", translog);
        return map;
    }
}
