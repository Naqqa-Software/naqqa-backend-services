package com.naqqa.elasticsearch.transport;

import com.naqqa.elasticsearch.common.io.stream.BytesStreamOutput;
import com.naqqa.elasticsearch.common.io.stream.ByteBufferStreamInput;
import com.naqqa.elasticsearch.common.io.stream.Writeable;

import java.io.IOException;

final class MessageCodec {

    private MessageCodec() {
    }

    record DecodedMessage(long requestId, byte status, int version, String action, byte[] payload) {
    }

    static byte[] serialize(Writeable writeable) throws IOException {
        BytesStreamOutput out = new BytesStreamOutput(64);
        writeable.writeTo(out);
        return out.toByteArray();
    }

    static byte[] encodeRequest(long requestId, String action, TransportRequest request, boolean compress, byte extraStatusFlags) throws IOException {
        byte[] body = serialize(request);
        byte status = TransportStatus.setRequest((byte) 0);
        status = (byte) (status | extraStatusFlags);
        if (compress) {
            body = Compressor.compress(body);
            status = TransportStatus.setCompress(status);
        }
        return wrap(requestId, status, TransportVersion.CURRENT, action, body);
    }

    static byte[] encodeResponse(long requestId, String action, TransportResponse response, boolean compress) throws IOException {
        byte[] body = serialize(response);
        byte status = (byte) 0;
        if (compress) {
            body = Compressor.compress(body);
            status = TransportStatus.setCompress(status);
        }
        return wrap(requestId, status, TransportVersion.CURRENT, action, body);
    }

    static byte[] encodeError(long requestId, String action, Exception exception) throws IOException {
        BytesStreamOutput out = new BytesStreamOutput(128);
        out.writeString(exception.getClass().getName());
        out.writeString(exception.getMessage() == null ? "" : exception.getMessage());
        byte status = TransportStatus.setError((byte) 0);
        return wrap(requestId, status, TransportVersion.CURRENT, action, out.toByteArray());
    }

    static DecodedMessage decodeBody(byte[] body) throws IOException {
        ByteBufferStreamInput in = new ByteBufferStreamInput(body);
        long requestId = in.readLong();
        byte status = in.readByte();
        int version = in.readInt();
        String action = in.readString();
        byte[] payload = new byte[in.available()];
        in.readBytes(payload, 0, payload.length);
        return new DecodedMessage(requestId, status, version, action, payload);
    }

    private static byte[] wrap(long requestId, byte status, int version, String action, byte[] payload) throws IOException {
        BytesStreamOutput body = new BytesStreamOutput(21 + action.length() + payload.length);
        body.writeLong(requestId);
        body.writeByte(status);
        body.writeInt(version);
        body.writeString(action);
        body.writeBytes(payload);
        byte[] bodyBytes = body.toByteArray();
        BytesStreamOutput frame = new BytesStreamOutput(TcpHeader.HEADER_SIZE + bodyBytes.length);
        frame.writeByte(TcpHeader.MARKER_MAGIC_0);
        frame.writeByte(TcpHeader.MARKER_MAGIC_1);
        frame.writeInt(bodyBytes.length);
        frame.writeBytes(bodyBytes);
        return frame.toByteArray();
    }
}
