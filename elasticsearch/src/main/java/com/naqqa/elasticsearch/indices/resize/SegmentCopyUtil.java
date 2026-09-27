package com.naqqa.elasticsearch.indices.resize;

import com.naqqa.elasticsearch.codec.Codec;
import com.naqqa.elasticsearch.codec.segment.SegmentCommitInfo;
import com.naqqa.elasticsearch.codec.segment.SegmentInfo;
import com.naqqa.elasticsearch.codec.segment.SegmentInfoFormat;
import com.naqqa.elasticsearch.codec.segment.SegmentInfos;
import com.naqqa.elasticsearch.index.engine.segment.SegmentReader;
import com.naqqa.elasticsearch.store.CodecUtil;
import com.naqqa.elasticsearch.store.Directory;
import com.naqqa.elasticsearch.store.IOContext;
import com.naqqa.elasticsearch.store.IndexInput;
import com.naqqa.elasticsearch.store.IndexOutput;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class SegmentCopyUtil {

    private static final SecureRandom RANDOM = new SecureRandom();

    private SegmentCopyUtil() {
    }

    static SegmentCommitInfo copySegment(Directory srcDir, Directory destDir, SegmentCommitInfo sci,
                                          String newName, Set<String> collectedFiles) throws IOException {
        SegmentReader reader = SegmentReader.open(srcDir, sci);
        try {
            SegmentInfo info = reader.info();
            String oldName = info.name();
            Set<String> renamedFiles = new LinkedHashSet<>();
            for (String oldFile : info.files()) {
                String newFile = newName + oldFile.substring(oldName.length());
                copyFileWithVerify(srcDir, destDir, oldFile, newFile);
                renamedFiles.add(newFile);
                collectedFiles.add(newFile);
            }
            long delGeneration = sci.delGeneration();
            int delCount = sci.delCount();
            if (delGeneration > 0) {
                String oldLiveDocs = Codec.liveDocsFileName(oldName, delGeneration);
                if (srcDir.fileExists(oldLiveDocs)) {
                    String newLiveDocs = Codec.liveDocsFileName(newName, delGeneration);
                    copyFileWithVerify(srcDir, destDir, oldLiveDocs, newLiveDocs);
                    collectedFiles.add(newLiveDocs);
                }
            }
            byte[] newId = new byte[16];
            RANDOM.nextBytes(newId);
            SegmentInfo newInfo = new SegmentInfo(newName, newId, info.maxDoc(), info.codecName(),
                renamedFiles, info.diagnostics(), info.attributes(), info.indexSort());
            String siFile = Codec.segmentInfoFileName(newName);
            try (IndexOutput out = destDir.createOutput(siFile, IOContext.DEFAULT)) {
                SegmentInfoFormat.write(out, newInfo);
            }
            collectedFiles.add(siFile);
            return new SegmentCommitInfo(newName, delGeneration, delCount);
        } finally {
            reader.decRef();
        }
    }

    static void copyFileWithVerify(Directory srcDir, Directory destDir, String srcName, String destName) throws IOException {
        destDir.copyFrom(srcDir, srcName, destName, IOContext.DEFAULT);
        verifyChecksum(destDir, destName);
    }

    private static void verifyChecksum(Directory dir, String name) throws IOException {
        try (IndexInput in = dir.openInput(name, IOContext.READ)) {
            if (name.endsWith("." + Codec.POSTINGS_EXT)) {
                drain(in);
            } else {
                CodecUtil.checksumEntireFile(in);
            }
        }
    }

    private static void drain(IndexInput in) throws IOException {
        long remaining = in.length();
        byte[] buffer = new byte[8192];
        while (remaining > 0) {
            int chunk = (int) Math.min(buffer.length, remaining);
            in.readBytes(buffer, 0, chunk);
            remaining -= chunk;
        }
    }

    static SegmentInfos commitNewSegments(Directory destDir, List<SegmentCommitInfo> commitInfos,
                                           Set<String> allFiles, long localCheckpoint, long maxSeqNo) throws IOException {
        destDir.sync(new ArrayList<>(allFiles));
        Map<String, String> userData = new LinkedHashMap<>();
        userData.put("local_checkpoint", Long.toString(localCheckpoint));
        userData.put("max_seq_no", Long.toString(maxSeqNo));
        SegmentInfos infos = new SegmentInfos(0, commitInfos, userData);
        return infos.commit(destDir);
    }
}
