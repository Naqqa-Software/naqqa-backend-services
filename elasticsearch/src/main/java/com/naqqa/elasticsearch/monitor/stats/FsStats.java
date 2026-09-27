package com.naqqa.elasticsearch.monitor.stats;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record FsStats(long totalBytes, long freeBytes, long availableBytes) {

    public static FsStats capture(List<Path> paths) {
        long total = 0;
        long free = 0;
        long available = 0;
        for (Path path : paths) {
            try {
                FileStore store = Files.getFileStore(path);
                total += store.getTotalSpace();
                free += store.getUnallocatedSpace();
                available += store.getUsableSpace();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return new FsStats(total, free, available);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        Map<String, Object> total = new LinkedHashMap<>();
        total.put("total_in_bytes", totalBytes);
        total.put("free_in_bytes", freeBytes);
        total.put("available_in_bytes", availableBytes);
        map.put("total", total);
        return map;
    }
}
