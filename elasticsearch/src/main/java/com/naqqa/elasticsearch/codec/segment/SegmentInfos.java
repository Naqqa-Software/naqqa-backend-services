package com.naqqa.elasticsearch.codec.segment;

import com.naqqa.elasticsearch.store.CodecUtil;
import com.naqqa.elasticsearch.store.Directory;
import com.naqqa.elasticsearch.store.IOContext;
import com.naqqa.elasticsearch.store.IndexInput;
import com.naqqa.elasticsearch.store.IndexOutput;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class SegmentInfos {

    public static final String CODEC_NAME = "NaqqaSegmentInfos";
    public static final int VERSION_START = 1;
    public static final int VERSION_CURRENT = 1;
    public static final String PENDING_PREFIX = "pending_segments_";
    public static final String COMMIT_PREFIX = "segments_";

    private final long generation;
    private final List<SegmentCommitInfo> segments;
    private final Map<String, String> userData;

    public SegmentInfos(long generation, List<SegmentCommitInfo> segments, Map<String, String> userData) {
        this.generation = generation;
        this.segments = segments;
        this.userData = userData;
    }

    public long generation() {
        return generation;
    }

    public List<SegmentCommitInfo> segments() {
        return segments;
    }

    public Map<String, String> userData() {
        return userData;
    }

    public static String fileNameForGeneration(long generation) {
        return COMMIT_PREFIX + Long.toString(generation, Character.MAX_RADIX);
    }

    public SegmentInfos commit(Directory dir) throws IOException {
        long newGeneration = generation + 1;
        String pendingName = PENDING_PREFIX + Long.toString(newGeneration, Character.MAX_RADIX);
        try (IndexOutput out = dir.createOutput(pendingName, IOContext.DEFAULT)) {
            CodecUtil.writeHeader(out, CODEC_NAME, VERSION_CURRENT);
            out.writeVLong(newGeneration);
            out.writeVInt(segments.size());
            for (SegmentCommitInfo sci : segments) {
                out.writeString(sci.segmentName());
                out.writeZLong(sci.delGeneration());
                out.writeVInt(sci.delCount());
            }
            out.writeMapOfStrings(userData);
            CodecUtil.writeFooter(out);
        }
        dir.sync(List.of(pendingName));
        String finalName = fileNameForGeneration(newGeneration);
        dir.rename(pendingName, finalName);
        dir.syncMetaData();
        return new SegmentInfos(newGeneration, segments, userData);
    }

    public static SegmentInfos readLatestCommit(Directory dir) throws IOException {
        long best = -1;
        String bestName = null;
        for (String name : dir.listAll()) {
            if (name.startsWith(COMMIT_PREFIX)) {
                try {
                    long gen = Long.parseLong(name.substring(COMMIT_PREFIX.length()), Character.MAX_RADIX);
                    if (gen > best) {
                        best = gen;
                        bestName = name;
                    }
                } catch (NumberFormatException ignored) {
                }
            }
        }
        if (bestName == null) {
            return new SegmentInfos(0, List.of(), new LinkedHashMap<>());
        }
        try (IndexInput in = dir.openInput(bestName, IOContext.DEFAULT)) {
            CodecUtil.checksumEntireFile(in);
            CodecUtil.checkHeader(in, CODEC_NAME, VERSION_START, VERSION_CURRENT);
            long generation = in.readVLong();
            int count = in.readVInt();
            List<SegmentCommitInfo> segments = new java.util.ArrayList<>();
            for (int i = 0; i < count; i++) {
                String name = in.readString();
                long delGen = in.readZLong();
                int delCount = in.readVInt();
                segments.add(new SegmentCommitInfo(name, delGen, delCount));
            }
            Map<String, String> userData = new LinkedHashMap<>(in.readMapOfStrings());
            return new SegmentInfos(generation, segments, userData);
        }
    }
}
