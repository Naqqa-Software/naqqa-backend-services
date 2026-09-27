package com.naqqa.elasticsearch.snapshots.model;

import com.naqqa.elasticsearch.snapshots.json.Json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record ShardSnapshotManifest(String index, int shard, List<FileInfo> files) {

    public byte[] toBytes() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("index", index);
        map.put("shard", shard);
        List<Object> fileList = new ArrayList<>();
        for (FileInfo f : files) {
            fileList.add(f.toMap());
        }
        map.put("files", fileList);
        return Json.write(map).getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    @SuppressWarnings("unchecked")
    public static ShardSnapshotManifest fromBytes(byte[] bytes) {
        Map<String, Object> map = Json.parseObject(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
        String index = (String) map.get("index");
        int shard = ((Number) map.get("shard")).intValue();
        List<Object> rawFiles = (List<Object>) map.get("files");
        List<FileInfo> files = new ArrayList<>();
        for (Object raw : rawFiles) {
            files.add(FileInfo.fromMap(raw));
        }
        return new ShardSnapshotManifest(index, shard, files);
    }
}
