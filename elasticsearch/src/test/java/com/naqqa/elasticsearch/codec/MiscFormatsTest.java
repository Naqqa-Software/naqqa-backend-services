package com.naqqa.elasticsearch.codec;

import com.naqqa.elasticsearch.codec.fieldinfos.DocValuesType;
import com.naqqa.elasticsearch.codec.fieldinfos.FieldInfo;
import com.naqqa.elasticsearch.codec.fieldinfos.FieldInfosFormat;
import com.naqqa.elasticsearch.codec.livedocs.FixedBitSet;
import com.naqqa.elasticsearch.codec.livedocs.LiveDocsFormat;
import com.naqqa.elasticsearch.codec.norms.NormsReader;
import com.naqqa.elasticsearch.codec.norms.NormsWriter;
import com.naqqa.elasticsearch.codec.segment.SegmentCommitInfo;
import com.naqqa.elasticsearch.codec.segment.SegmentInfo;
import com.naqqa.elasticsearch.codec.segment.SegmentInfoFormat;
import com.naqqa.elasticsearch.codec.segment.SegmentInfos;
import com.naqqa.elasticsearch.codec.termvectors.TermVectorTerm;
import com.naqqa.elasticsearch.codec.termvectors.TermVectorsReader;
import com.naqqa.elasticsearch.codec.termvectors.TermVectorsWriter;
import com.naqqa.elasticsearch.codec.vectors.VectorsFormat;
import com.naqqa.elasticsearch.codec.vectors.VectorsReader;
import com.naqqa.elasticsearch.store.ByteBuffersDirectory;
import com.naqqa.elasticsearch.store.IOContext;
import com.naqqa.elasticsearch.store.IndexInput;
import com.naqqa.elasticsearch.store.IndexOutput;
import com.naqqa.elasticsearch.test.Test;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static com.naqqa.elasticsearch.test.Assert.assertEquals;
import static com.naqqa.elasticsearch.test.Assert.assertTrue;

public final class MiscFormatsTest {

    @Test
    public void normsRoundTripExactSmallFloatValues() throws Exception {
        ByteBuffersDirectory dir = new ByteBuffersDirectory();
        int maxDoc = 200;
        int[] lengths = new int[maxDoc];
        Random random = new Random(9);
        for (int i = 0; i < maxDoc; i++) {
            lengths[i] = random.nextInt(5000);
        }
        try (IndexOutput out = dir.createOutput("norms", IOContext.DEFAULT)) {
            NormsWriter.write(out, maxDoc, lengths);
        }
        try (IndexInput in = dir.openInput("norms", IOContext.DEFAULT)) {
            NormsReader reader = new NormsReader(in);
            for (int i = 0; i < maxDoc; i++) {
                long decoded = reader.fieldLength(i);
                assertTrue(decoded <= lengths[i] * 1.2 + 16, "norm approximation too far for " + lengths[i]);
            }
        }
    }

    @Test
    public void termVectorsRoundTrip() throws Exception {
        ByteBuffersDirectory dir = new ByteBuffersDirectory();
        TermVectorTerm[][] docs = new TermVectorTerm[3][];
        docs[0] = new TermVectorTerm[]{
            new TermVectorTerm("hello".getBytes(StandardCharsets.UTF_8), 2, new int[]{0, 5}, new int[]{0, 20}, new int[]{5, 25}),
            new TermVectorTerm("world".getBytes(StandardCharsets.UTF_8), 1, new int[]{1}, new int[]{6}, new int[]{11})
        };
        docs[1] = new TermVectorTerm[0];
        docs[2] = new TermVectorTerm[]{
            new TermVectorTerm("zzz".getBytes(StandardCharsets.UTF_8), 1, new int[]{0}, new int[]{0}, new int[]{3})
        };
        int flags = TermVectorsWriter.HAS_POSITIONS | TermVectorsWriter.HAS_OFFSETS;
        try (IndexOutput out = dir.createOutput("tv", IOContext.DEFAULT)) {
            TermVectorsWriter.write(out, flags, docs);
        }
        try (IndexInput in = dir.openInput("tv", IOContext.DEFAULT)) {
            TermVectorsReader reader = new TermVectorsReader(in);
            assertEquals(3, reader.docCount());
            TermVectorTerm[] got0 = reader.get(0);
            assertEquals(2, got0.length);
            assertEquals("hello", new String(got0[0].term(), StandardCharsets.UTF_8));
            assertEquals(2, got0[0].freq());
            assertEquals(5, got0[0].positions()[1]);
            assertEquals(20, got0[0].startOffsets()[1]);
            assertEquals(0, reader.get(1).length);
        }
    }

    @Test
    public void liveDocsRoundTrip() throws Exception {
        ByteBuffersDirectory dir = new ByteBuffersDirectory();
        FixedBitSet live = FixedBitSet.allSet(100);
        live.clear(5);
        live.clear(50);
        try (IndexOutput out = dir.createOutput("live", IOContext.DEFAULT)) {
            LiveDocsFormat.write(out, live, 3L);
        }
        try (IndexInput in = dir.openInput("live", IOContext.DEFAULT)) {
            assertEquals(3L, LiveDocsFormat.readGeneration(in));
        }
        try (IndexInput in = dir.openInput("live", IOContext.DEFAULT)) {
            FixedBitSet loaded = LiveDocsFormat.read(in);
            assertEquals(98, loaded.cardinality());
            assertTrue(!loaded.get(5));
            assertTrue(!loaded.get(50));
            assertTrue(loaded.get(0));
        }
    }

    @Test
    public void fieldInfosRoundTrip() throws Exception {
        ByteBuffersDirectory dir = new ByteBuffersDirectory();
        Map<String, String> attrs = new LinkedHashMap<>();
        attrs.put("k", "v");
        FieldInfo[] infos = {
            new FieldInfo("title", 0, true, 15, true, false, true, DocValuesType.NONE, 0, 0, attrs),
            new FieldInfo("price", 1, false, 0, false, false, false, DocValuesType.NUMERIC, 0, 0, Map.of())
        };
        try (IndexOutput out = dir.createOutput("fi", IOContext.DEFAULT)) {
            FieldInfosFormat.write(out, infos);
        }
        try (IndexInput in = dir.openInput("fi", IOContext.DEFAULT)) {
            FieldInfo[] loaded = FieldInfosFormat.read(in);
            assertEquals(2, loaded.length);
            assertEquals("title", loaded[0].name());
            assertTrue(loaded[0].hasNorms());
            assertEquals("v", loaded[0].attributes().get("k"));
            assertEquals(DocValuesType.NUMERIC, loaded[1].docValuesType());
        }
    }

    @Test
    public void segmentInfoAndCommitPointRoundTrip() throws Exception {
        ByteBuffersDirectory dir = new ByteBuffersDirectory();
        byte[] id = new byte[16];
        new Random(1).nextBytes(id);
        SegmentInfo info = new SegmentInfo("_0", id, 1000, "Naqqa99", Set.of("_0.si"), Map.of("os", "test"), Map.of(), null);
        try (IndexOutput out = dir.createOutput("_0.si", IOContext.DEFAULT)) {
            SegmentInfoFormat.write(out, info);
        }
        try (IndexInput in = dir.openInput("_0.si", IOContext.DEFAULT)) {
            SegmentInfo loaded = SegmentInfoFormat.read(in, "_0", id);
            assertEquals(1000, loaded.maxDoc());
            assertEquals("Naqqa99", loaded.codecName());
        }

        SegmentInfos empty = SegmentInfos.readLatestCommit(dir);
        assertEquals(0L, empty.generation());
        SegmentInfos committed = empty.commit(dir);
        assertEquals(1L, committed.generation());
        SegmentInfos withSegment = new SegmentInfos(committed.generation(),
            List.of(new SegmentCommitInfo("_0", -1, 0)), Map.of("userkey", "userval"));
        SegmentInfos committed2 = withSegment.commit(dir);
        assertEquals(2L, committed2.generation());

        SegmentInfos latest = SegmentInfos.readLatestCommit(dir);
        assertEquals(2L, latest.generation());
        assertEquals(1, latest.segments().size());
        assertEquals("_0", latest.segments().get(0).segmentName());
        assertEquals("userval", latest.userData().get("userkey"));
        assertTrue(!dir.fileExists("pending_segments_1"));
        assertTrue(!dir.fileExists("pending_segments_2"));
    }

    @Test
    public void vectorsFormatRoundTrip() throws Exception {
        ByteBuffersDirectory dir = new ByteBuffersDirectory();
        float[][] vectors = new float[5][];
        vectors[0] = new float[]{1.0f, 2.0f, 3.0f};
        vectors[2] = new float[]{-1.5f, 0.0f, 9.25f};
        byte[] blob = {1, 2, 3, 4, 5};
        try (IndexOutput out = dir.createOutput("vec", IOContext.DEFAULT)) {
            VectorsFormat.writeFloatVectors(out, 3, vectors, blob);
        }
        try (IndexInput in = dir.openInput("vec", IOContext.DEFAULT)) {
            VectorsReader reader = new VectorsReader(in);
            assertEquals(3, reader.dims());
            assertTrue(java.util.Arrays.equals(vectors[0], reader.floatVector(0)));
            assertTrue(reader.floatVector(1) == null);
            assertTrue(java.util.Arrays.equals(vectors[2], reader.floatVector(2)));
            assertTrue(java.util.Arrays.equals(blob, reader.annGraphBlob()));
        }
    }
}
