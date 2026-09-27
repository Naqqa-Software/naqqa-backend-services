package com.naqqa.elasticsearch.codec.terms;

import com.naqqa.elasticsearch.codec.postings.PostingsEnum;
import com.naqqa.elasticsearch.codec.postings.PostingsFlags;
import com.naqqa.elasticsearch.codec.postings.PostingsWriter;
import com.naqqa.elasticsearch.codec.postings.TermStats;
import com.naqqa.elasticsearch.store.ByteBuffersDirectory;
import com.naqqa.elasticsearch.store.CorruptIndexException;
import com.naqqa.elasticsearch.store.IOContext;
import com.naqqa.elasticsearch.store.IndexInput;
import com.naqqa.elasticsearch.store.IndexOutput;
import com.naqqa.elasticsearch.test.Test;

import java.nio.charset.StandardCharsets;
import java.util.TreeMap;

import static com.naqqa.elasticsearch.test.Assert.assertEquals;
import static com.naqqa.elasticsearch.test.Assert.assertNull;
import static com.naqqa.elasticsearch.test.Assert.assertThrows;
import static com.naqqa.elasticsearch.test.Assert.assertTrue;

public final class BlockTermDictTest {

    @Test
    public void roundTripsManyTermsWithPostingsAndSeek() throws Exception {
        ByteBuffersDirectory dir = new ByteBuffersDirectory();
        TreeMap<String, Integer> terms = new TreeMap<>();
        for (int i = 0; i < 300; i++) {
            terms.put("term" + String.format("%04d", i), i);
        }

        try (IndexOutput postingsOut = dir.createOutput("postings", IOContext.DEFAULT);
             IndexOutput dictOut = dir.createOutput("dict", IOContext.DEFAULT)) {
            PostingsWriter pw = new PostingsWriter(postingsOut, PostingsFlags.FREQS);
            BlockTermDictWriter writer = new BlockTermDictWriter(dictOut);
            for (var entry : terms.entrySet()) {
                pw.startDoc(entry.getValue(), entry.getValue() + 1, (byte) 1);
                TermStats stats = pw.finishTerm();
                writer.addTerm(entry.getKey().getBytes(StandardCharsets.UTF_8), stats);
            }
            writer.finish();
        }

        try (IndexInput dictIn = dir.openInput("dict", IOContext.DEFAULT);
             IndexInput postingsIn = dir.openInput("postings", IOContext.DEFAULT)) {
            BlockTermDictReader reader = new BlockTermDictReader(dictIn, postingsIn);
            assertEquals((long) terms.size(), reader.numTerms());

            TermsEnum it = reader.iterator();
            for (var entry : terms.entrySet()) {
                byte[] t = it.next();
                assertEquals(entry.getKey(), new String(t, StandardCharsets.UTF_8));
                assertEquals(1, it.docFreq());
                PostingsEnum pe = it.postings(PostingsFlags.FREQS);
                assertEquals(entry.getValue().intValue(), pe.nextDoc());
                assertEquals(entry.getValue() + 1, pe.freq());
            }
            assertNull(it.next());

            TermsEnum seek = reader.iterator();
            assertTrue(seek.seekExact("term0150".getBytes(StandardCharsets.UTF_8)));
            assertEquals(1, seek.docFreq());
            PostingsEnum seekPe = seek.postings(PostingsFlags.FREQS);
            assertEquals(150, seekPe.nextDoc());

            TermsEnum ceil = reader.iterator();
            SeekStatus status = ceil.seekCeil("term0150a".getBytes(StandardCharsets.UTF_8));
            assertEquals(SeekStatus.NOT_FOUND, status);
            assertEquals("term0151", new String(ceil.term(), StandardCharsets.UTF_8));

            TermsEnum notFound = reader.iterator();
            assertTrue(!notFound.seekExact("zzzz".getBytes(StandardCharsets.UTF_8)));
        }
    }

    @Test
    public void corruptByteCausesChecksumFailureOnOpen() throws Exception {
        ByteBuffersDirectory dir = new ByteBuffersDirectory();
        try (IndexOutput postingsOut = dir.createOutput("postings2", IOContext.DEFAULT);
             IndexOutput dictOut = dir.createOutput("dict2", IOContext.DEFAULT)) {
            PostingsWriter pw = new PostingsWriter(postingsOut, PostingsFlags.DOCS_ONLY);
            BlockTermDictWriter writer = new BlockTermDictWriter(dictOut);
            for (int i = 0; i < 50; i++) {
                pw.startDoc(i, 1, (byte) 0);
                TermStats stats = pw.finishTerm();
                writer.addTerm(("word" + String.format("%04d", i)).getBytes(StandardCharsets.UTF_8), stats);
            }
            writer.finish();
        }
        byte[] bytes = dir.getFileContent("dict2");
        bytes[bytes.length / 2] ^= 0x7F;
        dir.setFileContent("dict2", bytes);

        assertThrows(CorruptIndexException.class, () -> {
            try (IndexInput dictIn = dir.openInput("dict2", IOContext.DEFAULT);
                 IndexInput postingsIn = dir.openInput("postings2", IOContext.DEFAULT)) {
                new BlockTermDictReader(dictIn, postingsIn);
            }
        });
    }
}
