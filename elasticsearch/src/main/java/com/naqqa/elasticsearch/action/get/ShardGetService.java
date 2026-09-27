package com.naqqa.elasticsearch.action.get;

import com.naqqa.elasticsearch.cluster.routing.ShardId;
import com.naqqa.elasticsearch.common.io.stream.StreamInput;
import com.naqqa.elasticsearch.common.io.stream.StreamOutput;
import com.naqqa.elasticsearch.index.engine.GetResult;
import com.naqqa.elasticsearch.index.shard.IndexShard;
import com.naqqa.elasticsearch.transport.TransportChannel;
import com.naqqa.elasticsearch.transport.TransportRequest;
import com.naqqa.elasticsearch.transport.TransportResponse;
import com.naqqa.elasticsearch.transport.TransportService;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

public final class ShardGetService {

    public static final String ACTION_GET = "indices:data/read/get[shard]";
    public static final String ACTION_MGET = "indices:data/read/mget[shard]";

    private final Function<ShardId, IndexShard> shardLookup;

    public ShardGetService(TransportService transportService, Function<ShardId, IndexShard> shardLookup) {
        this.shardLookup = shardLookup;
        transportService.registerRequestHandler(ACTION_GET, ShardGetRequest::new, this::handleGet);
        transportService.registerRequestHandler(ACTION_MGET, ShardMultiGetRequest::new, this::handleMultiGet);
    }

    private void handleGet(ShardGetRequest request, TransportChannel channel) throws Exception {
        IndexShard shard = shardLookup.apply(request.shardId());
        if (shard == null) {
            channel.sendResponse(new ShardGetResponse(request.shardId(), request.id(), false, -1L, null));
            return;
        }
        GetResult result = shard.get(request.id());
        if (!result.exists()) {
            channel.sendResponse(new ShardGetResponse(request.shardId(), request.id(), false, -1L, null));
        } else {
            channel.sendResponse(new ShardGetResponse(request.shardId(), request.id(), true, result.version(),
                result.source() == null ? new byte[0] : result.source().toBytesArray()));
        }
    }

    private void handleMultiGet(ShardMultiGetRequest request, TransportChannel channel) throws Exception {
        IndexShard shard = shardLookup.apply(request.shardId());
        List<ShardGetResponse> results = new ArrayList<>();
        for (String id : request.ids()) {
            if (shard == null) {
                results.add(new ShardGetResponse(request.shardId(), id, false, -1L, null));
                continue;
            }
            GetResult result = shard.get(id);
            if (!result.exists()) {
                results.add(new ShardGetResponse(request.shardId(), id, false, -1L, null));
            } else {
                results.add(new ShardGetResponse(request.shardId(), id, true, result.version(),
                    result.source() == null ? new byte[0] : result.source().toBytesArray()));
            }
        }
        channel.sendResponse(new ShardMultiGetResponse(request.shardId(), results));
    }

    static void writeShardId(StreamOutput out, ShardId shardId) throws IOException {
        out.writeString(shardId.index());
        out.writeVInt(shardId.id());
    }

    static ShardId readShardId(StreamInput in) throws IOException {
        return new ShardId(in.readString(), in.readVInt());
    }

    public static final class ShardGetRequest implements TransportRequest {
        private final ShardId shardId;
        private final String id;

        public ShardGetRequest(ShardId shardId, String id) {
            this.shardId = shardId;
            this.id = id;
        }

        ShardGetRequest(StreamInput in) throws IOException {
            this.shardId = readShardId(in);
            this.id = in.readString();
        }

        public ShardId shardId() {
            return shardId;
        }

        public String id() {
            return id;
        }

        @Override
        public void writeTo(StreamOutput out) throws IOException {
            writeShardId(out, shardId);
            out.writeString(id);
        }
    }

    public static final class ShardGetResponse implements TransportResponse {
        private final ShardId shardId;
        private final String id;
        private final boolean found;
        private final long version;
        private final byte[] source;

        public ShardGetResponse(ShardId shardId, String id, boolean found, long version, byte[] source) {
            this.shardId = shardId;
            this.id = id;
            this.found = found;
            this.version = version;
            this.source = source;
        }

        ShardGetResponse(StreamInput in) throws IOException {
            this.shardId = readShardId(in);
            this.id = in.readString();
            this.found = in.readBoolean();
            if (found) {
                this.version = in.readZLong();
                this.source = in.readByteArray();
            } else {
                this.version = -1L;
                this.source = null;
            }
        }

        public ShardId shardId() {
            return shardId;
        }

        public String id() {
            return id;
        }

        public boolean found() {
            return found;
        }

        public long version() {
            return version;
        }

        public byte[] source() {
            return source;
        }

        @Override
        public void writeTo(StreamOutput out) throws IOException {
            writeShardId(out, shardId);
            out.writeString(id);
            out.writeBoolean(found);
            if (found) {
                out.writeZLong(version);
                out.writeByteArray(source);
            }
        }
    }

    public static final class ShardMultiGetRequest implements TransportRequest {
        private final ShardId shardId;
        private final List<String> ids;

        public ShardMultiGetRequest(ShardId shardId, List<String> ids) {
            this.shardId = shardId;
            this.ids = ids;
        }

        ShardMultiGetRequest(StreamInput in) throws IOException {
            this.shardId = readShardId(in);
            this.ids = in.readStringList();
        }

        public ShardId shardId() {
            return shardId;
        }

        public List<String> ids() {
            return ids;
        }

        @Override
        public void writeTo(StreamOutput out) throws IOException {
            writeShardId(out, shardId);
            out.writeStringCollection(ids);
        }
    }

    public static final class ShardMultiGetResponse implements TransportResponse {
        private final ShardId shardId;
        private final List<ShardGetResponse> results;

        public ShardMultiGetResponse(ShardId shardId, List<ShardGetResponse> results) {
            this.shardId = shardId;
            this.results = results;
        }

        ShardMultiGetResponse(StreamInput in) throws IOException {
            this.shardId = readShardId(in);
            int count = in.readVInt();
            this.results = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                String id = in.readString();
                boolean found = in.readBoolean();
                if (found) {
                    long version = in.readZLong();
                    byte[] source = in.readByteArray();
                    results.add(new ShardGetResponse(shardId, id, true, version, source));
                } else {
                    results.add(new ShardGetResponse(shardId, id, false, -1L, null));
                }
            }
        }

        public ShardId shardId() {
            return shardId;
        }

        public List<ShardGetResponse> results() {
            return results;
        }

        @Override
        public void writeTo(StreamOutput out) throws IOException {
            writeShardId(out, shardId);
            out.writeVInt(results.size());
            for (ShardGetResponse r : results) {
                out.writeString(r.id());
                out.writeBoolean(r.found());
                if (r.found()) {
                    out.writeZLong(r.version());
                    out.writeByteArray(r.source());
                }
            }
        }
    }
}
