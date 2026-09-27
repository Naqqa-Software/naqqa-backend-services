package com.naqqa.elasticsearch.index.recovery;

import com.naqqa.elasticsearch.codec.segment.SegmentCommitInfo;
import com.naqqa.elasticsearch.codec.segment.SegmentInfos;
import com.naqqa.elasticsearch.index.engine.segment.SegmentReader;
import com.naqqa.elasticsearch.store.CorruptIndexException;
import com.naqqa.elasticsearch.store.Directory;
import com.naqqa.elasticsearch.store.IOContext;
import com.naqqa.elasticsearch.store.IndexInput;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.CRC32;

final class StoreFiles {

    private StoreFiles() {
    }

    static Set<String> latestCommitFileNames(Directory dir) throws IOException {
        SegmentInfos infos = SegmentInfos.readLatestCommit(dir);
        Set<String> names = new LinkedHashSet<>();
        if (infos.generation() > 0) {
            names.add(SegmentInfos.fileNameForGeneration(infos.generation()));
        }
        for (SegmentCommitInfo sci : infos.segments()) {
            SegmentReader reader = SegmentReader.open(dir, sci);
            try {
                names.addAll(reader.allFiles());
            } finally {
                reader.decRef();
            }
        }
        return names;
    }

    static List<StoreFileMetadata> latestCommitFiles(Directory dir) throws IOException {
        List<StoreFileMetadata> out = new ArrayList<>();
        for (String name : latestCommitFileNames(dir)) {
            out.add(metadataFor(dir, name));
        }
        return out;
    }

    static StoreFileMetadata metadataFor(Directory dir, String name) throws IOException {
        long length = dir.fileLength(name);
        long checksum = checksumOf(dir, name);
        return new StoreFileMetadata(name, length, checksum);
    }

    static long checksumOf(Directory dir, String name) throws IOException {
        try (IndexInput in = dir.openInput(name, IOContext.READ)) {
            CRC32 crc = new CRC32();
            long remaining = in.length();
            byte[] buffer = new byte[8192];
            while (remaining > 0) {
                int chunk = (int) Math.min(buffer.length, remaining);
                in.readBytes(buffer, 0, chunk);
                crc.update(buffer, 0, chunk);
                remaining -= chunk;
            }
            return crc.getValue();
        }
    }

    static void verifyChecksum(Directory dir, StoreFileMetadata expected) throws IOException {
        long actualLength = dir.fileLength(expected.name());
        if (actualLength != expected.length()) {
            throw new CorruptIndexException(
                "length mismatch for [" + expected.name() + "]: expected=" + expected.length() + " actual=" + actualLength, expected.name());
        }
        long actualChecksum = checksumOf(dir, expected.name());
        if (actualChecksum != expected.checksum()) {
            throw new CorruptIndexException(
                "checksum mismatch for [" + expected.name() + "]: expected=" + expected.checksum() + " actual=" + actualChecksum, expected.name());
        }
    }

    static Map<String, StoreFileMetadata> byName(List<StoreFileMetadata> files) {
        Map<String, StoreFileMetadata> map = new LinkedHashMap<>();
        for (StoreFileMetadata f : files) {
            map.put(f.name(), f);
        }
        return map;
    }
}
