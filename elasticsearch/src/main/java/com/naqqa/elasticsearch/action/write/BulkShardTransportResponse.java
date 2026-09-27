package com.naqqa.elasticsearch.action.write;

import com.naqqa.elasticsearch.common.io.stream.StreamInput;
import com.naqqa.elasticsearch.common.io.stream.StreamOutput;
import com.naqqa.elasticsearch.common.json.JsonValue;
import com.naqqa.elasticsearch.common.json.JsonWriter;
import com.naqqa.elasticsearch.rest.document.DocumentActionService;
import com.naqqa.elasticsearch.transport.TransportResponse;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class BulkShardTransportResponse implements TransportResponse {

    private final byte[] resultsJson;

    public BulkShardTransportResponse(List<DocumentActionService.BulkItemResult> results) {
        this.resultsJson = encode(results);
    }

    public BulkShardTransportResponse(StreamInput in) throws IOException {
        this.resultsJson = in.readByteArray();
    }

    public List<DocumentActionService.BulkItemResult> results() {
        return decode(resultsJson);
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        out.writeByteArray(resultsJson);
    }

    private static byte[] encode(List<DocumentActionService.BulkItemResult> results) {
        List<Object> encoded = new ArrayList<>(results.size());
        for (DocumentActionService.BulkItemResult r : results) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("action", r.action());
            m.put("index", r.index());
            m.put("id", r.id());
            m.put("status", r.status());
            m.put("version", r.version());
            m.put("seq_no", r.seqNo());
            m.put("primary_term", r.primaryTerm());
            m.put("result", r.result());
            m.put("found", r.found());
            m.put("error", r.error());
            encoded.add(m);
        }
        return JsonWriter.toJsonBytes(encoded, false);
    }

    @SuppressWarnings("unchecked")
    private static List<DocumentActionService.BulkItemResult> decode(byte[] bytes) {
        List<Object> raw = (List<Object>) JsonValue.parse(bytes).toJava();
        List<DocumentActionService.BulkItemResult> results = new ArrayList<>(raw.size());
        for (Object o : raw) {
            Map<String, Object> m = (Map<String, Object>) o;
            results.add(new DocumentActionService.BulkItemResult(
                (String) m.get("action"),
                (String) m.get("index"),
                (String) m.get("id"),
                asInt(m.get("status")),
                asLong(m.get("version")),
                asLong(m.get("seq_no")),
                asLong(m.get("primary_term")),
                (String) m.get("result"),
                Boolean.TRUE.equals(m.get("found")),
                (Map<String, Object>) m.get("error")));
        }
        return results;
    }

    private static long asLong(Object o) {
        return o instanceof Number n ? n.longValue() : -1L;
    }

    private static int asInt(Object o) {
        return o instanceof Number n ? n.intValue() : 0;
    }
}
