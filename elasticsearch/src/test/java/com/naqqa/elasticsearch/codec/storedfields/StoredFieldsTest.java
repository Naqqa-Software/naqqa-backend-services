package com.naqqa.elasticsearch.codec.storedfields;

import com.naqqa.elasticsearch.store.ByteBuffersDirectory;
import com.naqqa.elasticsearch.store.CorruptIndexException;
import com.naqqa.elasticsearch.store.IOContext;
import com.naqqa.elasticsearch.store.IndexInput;
import com.naqqa.elasticsearch.store.IndexOutput;
import com.naqqa.elasticsearch.test.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static com.naqqa.elasticsearch.test.Assert.assertEquals;
import static com.naqqa.elasticsearch.test.Assert.assertThrows;
import static com.naqqa.elasticsearch.test.Assert.assertTrue;

public final class StoredFieldsTest {

    private void roundTrip(byte mode) throws Exception {
        ByteBuffersDirectory dir = new ByteBuffersDirectory();
        Random random = new Random(mode + 100);
        List<byte[]> docs = new ArrayList<>();
        for (int i = 0; i < 400; i++) {
            String s = "{\"id\":" + i + ",\"text\":\"hello world number " + i + " padding padding\"}";
            docs.add(s.getBytes(StandardCharsets.UTF_8));
        }
        String name = "fields-" + mode;
        try (IndexOutput out = dir.createOutput(name, IOContext.DEFAULT)) {
            StoredFieldsWriter writer = new StoredFieldsWriter(out, mode);
            for (byte[] d : docs) {
                writer.addDocument(d);
            }
            writer.finish();
        }
        try (IndexInput in = dir.openInput(name, IOContext.DEFAULT)) {
            StoredFieldsReader reader = new StoredFieldsReader(in);
            assertEquals(docs.size(), reader.docCount());
            for (int i = 0; i < docs.size(); i++) {
                byte[] got = reader.document(i);
                assertTrue(java.util.Arrays.equals(docs.get(i), got), "mismatch at doc " + i);
            }
        }
    }

    @Test
    public void roundTripsLz4Fast() throws Exception {
        roundTrip(StoredFieldsWriter.COMPRESSION_LZ4_FAST);
    }

    @Test
    public void roundTripsLz4High() throws Exception {
        roundTrip(StoredFieldsWriter.COMPRESSION_LZ4_HIGH);
    }

    @Test
    public void roundTripsDeflateBest() throws Exception {
        roundTrip(StoredFieldsWriter.COMPRESSION_DEFLATE_BEST);
    }

    @Test
    public void corruptByteCausesChecksumFailureOnOpen() throws Exception {
        ByteBuffersDirectory dir = new ByteBuffersDirectory();
        try (IndexOutput out = dir.createOutput("f", IOContext.DEFAULT)) {
            StoredFieldsWriter writer = new StoredFieldsWriter(out, StoredFieldsWriter.COMPRESSION_LZ4_HIGH);
            for (int i = 0; i < 20; i++) {
                writer.addDocument(("doc-" + i).getBytes(StandardCharsets.UTF_8));
            }
            writer.finish();
        }
        byte[] bytes = dir.getFileContent("f");
        bytes[bytes.length / 3] ^= 0x55;
        dir.setFileContent("f", bytes);
        assertThrows(CorruptIndexException.class, () -> {
            try (IndexInput in = dir.openInput("f", IOContext.DEFAULT)) {
                new StoredFieldsReader(in);
            }
        });
    }
}
