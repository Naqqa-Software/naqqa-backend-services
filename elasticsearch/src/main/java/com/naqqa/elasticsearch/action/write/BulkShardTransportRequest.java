package com.naqqa.elasticsearch.action.write;

import com.naqqa.elasticsearch.cluster.routing.ShardId;
import com.naqqa.elasticsearch.common.io.stream.StreamInput;
import com.naqqa.elasticsearch.common.io.stream.StreamOutput;
import com.naqqa.elasticsearch.common.json.JsonValue;
import com.naqqa.elasticsearch.common.json.JsonWriter;
import com.naqqa.elasticsearch.rest.document.DocumentActionService;
import com.naqqa.elasticsearch.transport.TransportRequest;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class BulkShardTransportRequest implements TransportRequest {

    private final String index;
    private final int shard;
    private final String defaultIndex;
    private final byte[] itemsJson;
    private final String globalRefresh;

    public BulkShardTransportRequest(ShardId shardId, String defaultIndex, List<DocumentActionService.BulkItem> items,
                                      String globalRefresh) {
        this.index = shardId.index();
        this.shard = shardId.id();
        this.defaultIndex = defaultIndex;
        this.itemsJson = encodeItems(items);
        this.globalRefresh = globalRefresh;
    }

    public BulkShardTransportRequest(StreamInput in) throws IOException {
        this.index = in.readString();
        this.shard = in.readVInt();
        this.defaultIndex = in.readOptionalString();
        this.itemsJson = in.readByteArray();
        this.globalRefresh = in.readOptionalString();
    }

    public ShardId shardId() {
        return new ShardId(index, shard);
    }

    public String defaultIndex() {
        return defaultIndex;
    }

    public String globalRefresh() {
        return globalRefresh;
    }

    public List<DocumentActionService.BulkItem> items() {
        return decodeItems(itemsJson);
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        out.writeString(index);
        out.writeVInt(shard);
        out.writeOptionalString(defaultIndex);
        out.writeByteArray(itemsJson);
        out.writeOptionalString(globalRefresh);
    }

    private static byte[] encodeItems(List<DocumentActionService.BulkItem> items) {
        List<Object> encoded = new ArrayList<>(items.size());
        for (DocumentActionService.BulkItem item : items) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("action", item.action());
            m.put("index", item.index());
            m.put("id", item.id());
            m.put("source", item.source());
            m.put("doc", item.doc());
            m.put("upsert", item.upsert());
            m.put("doc_as_upsert", item.docAsUpsert());
            m.put("version", item.version());
            m.put("version_type", item.versionType());
            m.put("if_seq_no", item.ifSeqNo());
            m.put("if_primary_term", item.ifPrimaryTerm());
            m.put("routing", item.routing());
            m.put("op_type", item.opType());
            encoded.add(m);
        }
        return JsonWriter.toJsonBytes(encoded, false);
    }

    @SuppressWarnings("unchecked")
    private static List<DocumentActionService.BulkItem> decodeItems(byte[] bytes) {
        List<Object> raw = (List<Object>) JsonValue.parse(bytes).toJava();
        List<DocumentActionService.BulkItem> items = new ArrayList<>(raw.size());
        for (Object o : raw) {
            Map<String, Object> m = (Map<String, Object>) o;
            items.add(new DocumentActionService.BulkItem(
                (String) m.get("action"),
                (String) m.get("index"),
                (String) m.get("id"),
                (Map<String, Object>) m.get("source"),
                (Map<String, Object>) m.get("doc"),
                (Map<String, Object>) m.get("upsert"),
                Boolean.TRUE.equals(m.get("doc_as_upsert")),
                asLong(m.get("version")),
                (String) m.get("version_type"),
                asLong(m.get("if_seq_no")),
                asLong(m.get("if_primary_term")),
                (String) m.get("routing"),
                (String) m.get("op_type")));
        }
        return items;
    }

    private static Long asLong(Object o) {
        return o instanceof Number n ? n.longValue() : null;
    }
}
