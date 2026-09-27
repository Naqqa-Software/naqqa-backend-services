package com.naqqa.elasticsearch.codec;

import com.naqqa.elasticsearch.codec.docvalues.NumericDocValuesReader;
import com.naqqa.elasticsearch.codec.docvalues.NumericDocValuesWriter;
import com.naqqa.elasticsearch.codec.fieldinfos.DocValuesType;
import com.naqqa.elasticsearch.codec.fieldinfos.FieldInfo;
import com.naqqa.elasticsearch.codec.fieldinfos.FieldInfosFormat;
import com.naqqa.elasticsearch.codec.livedocs.FixedBitSet;
import com.naqqa.elasticsearch.codec.livedocs.LiveDocsFormat;
import com.naqqa.elasticsearch.codec.postings.PostingsEnum;
import com.naqqa.elasticsearch.codec.postings.PostingsFlags;
import com.naqqa.elasticsearch.codec.postings.PostingsWriter;
import com.naqqa.elasticsearch.codec.postings.TermStats;
import com.naqqa.elasticsearch.codec.segment.SegmentCommitInfo;
import com.naqqa.elasticsearch.codec.segment.SegmentInfo;
import com.naqqa.elasticsearch.codec.segment.SegmentInfoFormat;
import com.naqqa.elasticsearch.codec.segment.SegmentInfos;
import com.naqqa.elasticsearch.codec.storedfields.StoredFieldsReader;
import com.naqqa.elasticsearch.codec.storedfields.StoredFieldsWriter;
import com.naqqa.elasticsearch.codec.terms.BlockTermDictReader;
import com.naqqa.elasticsearch.codec.terms.BlockTermDictWriter;
import com.naqqa.elasticsearch.codec.terms.TermsEnum;
import com.naqqa.elasticsearch.store.FSDirectory;
import com.naqqa.elasticsearch.store.IOContext;
import com.naqqa.elasticsearch.store.IndexInput;
import com.naqqa.elasticsearch.store.IndexOutput;
import com.naqqa.elasticsearch.test.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;

import static com.naqqa.elasticsearch.test.Assert.assertEquals;
import static com.naqqa.elasticsearch.test.Assert.assertTrue;

public final class CodecIntegrationTest {

    @Test
    public void writesAndReadsBackAFullSegment() throws Exception {
        Path dirPath = Files.createTempDirectory("naqqa-codec-it");
        FSDirectory dir = new FSDirectory(dirPath);
        String segmentName = "_0";
        int maxDoc = 200;

        String[] words = {"apple", "banana", "cherry", "date", "egg"};
        String[] titleField = new String[maxDoc];
        long[] priceField = new long[maxDoc];
        Random random = new Random(42);
        TreeMap<String, List<Integer>> postingsByTerm = new TreeMap<>();
        for (int d = 0; d < maxDoc; d++) {
            String word = words[d % words.length];
            titleField[d] = word;
            priceField[d] = 100 + d;
            postingsByTerm.computeIfAbsent(word, k -> new java.util.ArrayList<>()).add(d);
        }

        try (IndexOutput postingsOut = dir.createOutput(Codec.postingsFileName(segmentName, "title"), IOContext.DEFAULT);
             IndexOutput dictOut = dir.createOutput(Codec.termsDictFileName(segmentName, "title"), IOContext.DEFAULT)) {
            PostingsWriter pw = new PostingsWriter(postingsOut, PostingsFlags.FREQS);
            BlockTermDictWriter tw = new BlockTermDictWriter(dictOut);
            for (var entry : postingsByTerm.entrySet()) {
                for (int docId : entry.getValue()) {
                    pw.startDoc(docId, 1, (byte) 1);
                }
                TermStats stats = pw.finishTerm();
                tw.addTerm(entry.getKey().getBytes(StandardCharsets.UTF_8), stats);
            }
            tw.finish();
        }

        try (IndexOutput out = dir.createOutput(Codec.docValuesFileName(segmentName, "price"), IOContext.DEFAULT)) {
            NumericDocValuesWriter.write(out, maxDoc, priceField, null);
        }

        try (IndexOutput out = dir.createOutput(Codec.storedFieldsFileName(segmentName), IOContext.DEFAULT)) {
            StoredFieldsWriter writer = new StoredFieldsWriter(out, StoredFieldsWriter.COMPRESSION_LZ4_HIGH);
            for (int d = 0; d < maxDoc; d++) {
                String src = "{\"title\":\"" + titleField[d] + "\",\"price\":" + priceField[d] + "}";
                writer.addDocument(src.getBytes(StandardCharsets.UTF_8));
            }
            writer.finish();
        }

        FixedBitSet live = FixedBitSet.allSet(maxDoc);
        live.clear(3);
        live.clear(4);
        try (IndexOutput out = dir.createOutput(Codec.liveDocsFileName(segmentName, 1), IOContext.DEFAULT)) {
            LiveDocsFormat.write(out, live, 1);
        }

        FieldInfo[] fieldInfos = {
            new FieldInfo("title", 0, true, PostingsFlags.FREQS, false, false, false, DocValuesType.NONE, 0, 0, Map.of()),
            new FieldInfo("price", 1, false, 0, false, false, false, DocValuesType.NUMERIC, 0, 0, Map.of())
        };
        try (IndexOutput out = dir.createOutput(Codec.fieldInfosFileName(segmentName), IOContext.DEFAULT)) {
            FieldInfosFormat.write(out, fieldInfos);
        }

        byte[] segId = new byte[16];
        random.nextBytes(segId);
        Set<String> segFiles = Set.of(
            Codec.postingsFileName(segmentName, "title"), Codec.termsDictFileName(segmentName, "title"),
            Codec.docValuesFileName(segmentName, "price"), Codec.storedFieldsFileName(segmentName),
            Codec.fieldInfosFileName(segmentName));
        SegmentInfo segInfo = new SegmentInfo(segmentName, segId, maxDoc, Codec.NAME, segFiles, Map.of(), Map.of(), null);
        try (IndexOutput out = dir.createOutput(Codec.segmentInfoFileName(segmentName), IOContext.DEFAULT)) {
            SegmentInfoFormat.write(out, segInfo);
        }

        SegmentInfos commit = SegmentInfos.readLatestCommit(dir)
            .commit(dir);
        SegmentInfos withSegment = new SegmentInfos(commit.generation(),
            List.of(new SegmentCommitInfo(segmentName, 1, 2)), Map.of());
        withSegment.commit(dir);

        SegmentInfos reopened = SegmentInfos.readLatestCommit(dir);
        assertEquals(1, reopened.segments().size());
        SegmentCommitInfo sci = reopened.segments().get(0);
        assertEquals(segmentName, sci.segmentName());
        assertEquals(2, sci.delCount());

        try (IndexInput in = dir.openInput(Codec.segmentInfoFileName(sci.segmentName()), IOContext.DEFAULT)) {
            SegmentInfo loadedInfo = SegmentInfoFormat.read(in, sci.segmentName(), segId);
            assertEquals(maxDoc, loadedInfo.maxDoc());
        }

        try (IndexInput in = dir.openInput(Codec.fieldInfosFileName(segmentName), IOContext.DEFAULT)) {
            FieldInfo[] loadedFields = FieldInfosFormat.read(in);
            assertEquals(2, loadedFields.length);
        }

        try (IndexInput liveIn = dir.openInput(Codec.liveDocsFileName(segmentName, sci.delGeneration()), IOContext.DEFAULT)) {
            FixedBitSet loadedLive = LiveDocsFormat.read(liveIn);
            assertEquals(maxDoc - 2, loadedLive.cardinality());
            assertTrue(!loadedLive.get(3));
        }

        try (IndexInput dictIn = dir.openInput(Codec.termsDictFileName(segmentName, "title"), IOContext.DEFAULT);
             IndexInput postingsIn = dir.openInput(Codec.postingsFileName(segmentName, "title"), IOContext.DEFAULT)) {
            BlockTermDictReader reader = new BlockTermDictReader(dictIn, postingsIn);
            TermsEnum te = reader.iterator();
            for (var entry : postingsByTerm.entrySet()) {
                byte[] t = te.next();
                assertEquals(entry.getKey(), new String(t, StandardCharsets.UTF_8));
                assertEquals(entry.getValue().size(), te.docFreq());
                PostingsEnum pe = te.postings(PostingsFlags.FREQS);
                for (int expectedDoc : entry.getValue()) {
                    assertEquals(expectedDoc, pe.nextDoc());
                }
            }
        }

        try (IndexInput in = dir.openInput(Codec.docValuesFileName(segmentName, "price"), IOContext.DEFAULT)) {
            NumericDocValuesReader dvReader = new NumericDocValuesReader(in);
            for (int d = 0; d < maxDoc; d++) {
                assertTrue(dvReader.advanceExact(d));
                assertEquals(priceField[d], dvReader.longValue());
            }
        }

        try (IndexInput in = dir.openInput(Codec.storedFieldsFileName(segmentName), IOContext.DEFAULT)) {
            StoredFieldsReader sfReader = new StoredFieldsReader(in);
            byte[] doc7 = sfReader.document(7);
            assertTrue(new String(doc7, StandardCharsets.UTF_8).contains(titleField[7]));
        }

        dir.close();
    }
}
