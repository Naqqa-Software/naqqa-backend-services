package com.naqqa.elasticsearch.codec.fst;

import com.naqqa.elasticsearch.store.ByteArrayDataInput;
import com.naqqa.elasticsearch.store.BytesDataOutput;
import com.naqqa.elasticsearch.test.Test;

import java.nio.charset.StandardCharsets;
import java.util.Random;
import java.util.TreeMap;

import static com.naqqa.elasticsearch.test.Assert.assertEquals;
import static com.naqqa.elasticsearch.test.Assert.assertFalse;
import static com.naqqa.elasticsearch.test.Assert.assertNull;
import static com.naqqa.elasticsearch.test.Assert.assertTrue;

public final class FSTTest {

    @Test
    public void fstMatchesTreeMapForLookupAndIteration() throws Exception {
        TreeMap<String, Long> reference = new TreeMap<>();
        Random random = new Random(99);
        String[] words = {"apple", "application", "apply", "banana", "band", "bandana", "can", "candy", "cane", "zoo"};
        for (int i = 0; i < words.length; i++) {
            reference.put(words[i], (long) (i * 17 + random.nextInt(1000)));
        }
        FSTBuilder builder = new FSTBuilder();
        for (var entry : reference.entrySet()) {
            builder.add(entry.getKey().getBytes(StandardCharsets.UTF_8), entry.getValue());
        }
        FST fst = builder.build();

        for (var entry : reference.entrySet()) {
            Long got = fst.get(entry.getKey().getBytes(StandardCharsets.UTF_8));
            assertEquals(entry.getValue(), got, "lookup mismatch for " + entry.getKey());
        }
        assertNull(fst.get("missing".getBytes(StandardCharsets.UTF_8)));

        FSTEnum it = fst.iterator();
        for (var entry : reference.entrySet()) {
            assertTrue(it.next(), "expected next term");
            assertEquals(entry.getKey(), new String(it.term(), StandardCharsets.UTF_8));
            assertEquals((long) entry.getValue(), it.output());
        }
        assertFalse(it.next());

        FSTEnum ceil = fst.iterator();
        assertTrue(ceil.seekCeil("ao".getBytes(StandardCharsets.UTF_8)));
        assertEquals("apple", new String(ceil.term(), StandardCharsets.UTF_8));

        FSTEnum ceilAfterLast = fst.iterator();
        assertFalse(ceilAfterLast.seekCeil("zzz".getBytes(StandardCharsets.UTF_8)));

        FSTEnum exact = fst.iterator();
        assertTrue(exact.seekExact("band".getBytes(StandardCharsets.UTF_8)));
        assertEquals((long) reference.get("band"), exact.output());
        assertTrue(exact.next());
        assertEquals("bandana", new String(exact.term(), StandardCharsets.UTF_8));

        FSTEnum missing = fst.iterator();
        assertFalse(missing.seekExact("bandit".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    public void fstSerializationRoundTrips() throws Exception {
        FSTBuilder builder = new FSTBuilder();
        String[] words = {"a", "ab", "abc", "b"};
        for (int i = 0; i < words.length; i++) {
            builder.add(words[i].getBytes(StandardCharsets.UTF_8), i * 3L);
        }
        FST fst = builder.build();
        BytesDataOutput out = new BytesDataOutput();
        fst.save(out);
        ByteArrayDataInput in = out.toDataInput();
        FST reloaded = FST.load(in);
        for (int i = 0; i < words.length; i++) {
            assertEquals((Long) (i * 3L), reloaded.get(words[i].getBytes(StandardCharsets.UTF_8)));
        }
    }
}
