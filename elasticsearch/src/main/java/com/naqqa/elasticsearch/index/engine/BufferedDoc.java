package com.naqqa.elasticsearch.index.engine;

import com.naqqa.elasticsearch.index.mapper.IndexableField;
import com.naqqa.elasticsearch.index.mapper.IndexedTerm;
import com.naqqa.elasticsearch.index.mapper.ParsedDocument;

import java.util.List;

public record BufferedDoc(String id, long seqNo, long primaryTerm, long version, boolean deleted, ParsedDocument doc,
                           long ramBytesUsed) {

    private static final long BASE_OVERHEAD_BYTES = 160L;
    private static final long FIELD_OVERHEAD_BYTES = 48L;
    private static final long TERM_OVERHEAD_BYTES = 40L;
    private static final long VALUE_OVERHEAD_BYTES = 16L;

    public static BufferedDoc indexed(String id, long seqNo, long primaryTerm, long version, ParsedDocument doc) {
        return new BufferedDoc(id, seqNo, primaryTerm, version, false, doc, estimateBytes(id, doc));
    }

    public static BufferedDoc tombstone(String id, long seqNo, long primaryTerm, long version) {
        return new BufferedDoc(id, seqNo, primaryTerm, version, true, null, BASE_OVERHEAD_BYTES + charBytes(id));
    }

    private static long estimateBytes(String id, ParsedDocument doc) {
        long size = BASE_OVERHEAD_BYTES + charBytes(id);
        if (doc == null) {
            return size;
        }
        size += fieldsBytes(doc.rootFields());
        List<List<IndexableField>> nested = doc.nestedDocuments();
        if (nested != null) {
            for (List<IndexableField> fields : nested) {
                size += fieldsBytes(fields);
            }
        }
        return size;
    }

    private static long fieldsBytes(List<IndexableField> fields) {
        if (fields == null) {
            return 0L;
        }
        long size = 0L;
        for (IndexableField f : fields) {
            size += FIELD_OVERHEAD_BYTES + charBytes(f.name());
            List<IndexedTerm> terms = f.terms();
            if (terms != null) {
                for (IndexedTerm t : terms) {
                    size += TERM_OVERHEAD_BYTES + charBytes(t.term());
                }
            }
            byte[] binary = f.binaryValue();
            if (binary != null) {
                size += VALUE_OVERHEAD_BYTES + binary.length;
            }
            byte[][] pointDims = f.pointDims();
            if (pointDims != null) {
                for (byte[] d : pointDims) {
                    size += VALUE_OVERHEAD_BYTES + d.length;
                }
            }
            List<byte[]> sortedSet = f.sortedSetValues();
            if (sortedSet != null) {
                for (byte[] v : sortedSet) {
                    size += VALUE_OVERHEAD_BYTES + v.length;
                }
            }
            float[] vectorFloats = f.vectorFloats();
            if (vectorFloats != null) {
                size += VALUE_OVERHEAD_BYTES + (long) vectorFloats.length * 4;
            }
            byte[] vectorBytes = f.vectorBytes();
            if (vectorBytes != null) {
                size += VALUE_OVERHEAD_BYTES + vectorBytes.length;
            }
        }
        return size;
    }

    private static long charBytes(String s) {
        return s == null ? 0L : VALUE_OVERHEAD_BYTES + (long) s.length() * 2;
    }
}
