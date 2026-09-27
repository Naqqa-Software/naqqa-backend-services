package com.naqqa.elasticsearch.index.engine;

import com.naqqa.elasticsearch.index.mapper.ParsedDocument;

public record BufferedDoc(String id, long seqNo, long primaryTerm, long version, boolean deleted, ParsedDocument doc) {

    public static BufferedDoc indexed(String id, long seqNo, long primaryTerm, long version, ParsedDocument doc) {
        return new BufferedDoc(id, seqNo, primaryTerm, version, false, doc);
    }

    public static BufferedDoc tombstone(String id, long seqNo, long primaryTerm, long version) {
        return new BufferedDoc(id, seqNo, primaryTerm, version, true, null);
    }
}
