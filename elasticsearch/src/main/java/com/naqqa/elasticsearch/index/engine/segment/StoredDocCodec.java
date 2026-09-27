package com.naqqa.elasticsearch.index.engine.segment;

import com.naqqa.elasticsearch.store.BytesDataOutput;
import com.naqqa.elasticsearch.store.ByteArrayDataInput;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

public final class StoredDocCodec {

    private StoredDocCodec() {
    }

    public static byte[] encode(String id, long seqNo, long primaryTerm, long version, byte[] source,
                                 Map<String, byte[]> extraStoredFields) {
        try {
            BytesDataOutput out = new BytesDataOutput(source.length + 64);
            byte[] idBytes = id.getBytes(StandardCharsets.UTF_8);
            out.writeVInt(idBytes.length);
            out.writeBytes(idBytes, 0, idBytes.length);
            out.writeVLong(seqNo);
            out.writeVLong(primaryTerm);
            out.writeVLong(version);
            out.writeVInt(source.length);
            out.writeBytes(source, 0, source.length);
            out.writeVInt(extraStoredFields.size());
            for (Map.Entry<String, byte[]> e : extraStoredFields.entrySet()) {
                byte[] nameBytes = e.getKey().getBytes(StandardCharsets.UTF_8);
                out.writeVInt(nameBytes.length);
                out.writeBytes(nameBytes, 0, nameBytes.length);
                byte[] value = e.getValue();
                out.writeVInt(value.length);
                out.writeBytes(value, 0, value.length);
            }
            return out.toArrayCopy();
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    public record Decoded(String id, long seqNo, long primaryTerm, long version, byte[] source, Map<String, byte[]> extraStoredFields) {
    }

    public static Decoded decode(byte[] blob) {
        try {
            ByteArrayDataInput in = new ByteArrayDataInput(blob);
            int idLen = in.readVInt();
            byte[] idBytes = new byte[idLen];
            in.readBytes(idBytes, 0, idLen);
            String id = new String(idBytes, StandardCharsets.UTF_8);
            long seqNo = in.readVLong();
            long primaryTerm = in.readVLong();
            long version = in.readVLong();
            int sourceLen = in.readVInt();
            byte[] source = new byte[sourceLen];
            in.readBytes(source, 0, sourceLen);
            Map<String, byte[]> extra = new LinkedHashMap<>();
            if (!in.eof()) {
                int extraCount = in.readVInt();
                for (int i = 0; i < extraCount; i++) {
                    int nameLen = in.readVInt();
                    byte[] nameBytes = new byte[nameLen];
                    in.readBytes(nameBytes, 0, nameLen);
                    String name = new String(nameBytes, StandardCharsets.UTF_8);
                    int valLen = in.readVInt();
                    byte[] value = new byte[valLen];
                    in.readBytes(value, 0, valLen);
                    extra.put(name, value);
                }
            }
            return new Decoded(id, seqNo, primaryTerm, version, source, extra);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
