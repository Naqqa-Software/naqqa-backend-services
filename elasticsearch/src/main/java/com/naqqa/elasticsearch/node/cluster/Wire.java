package com.naqqa.elasticsearch.node.cluster;

import com.naqqa.elasticsearch.common.io.stream.ByteBufferStreamInput;
import com.naqqa.elasticsearch.common.io.stream.BytesStreamOutput;
import com.naqqa.elasticsearch.common.io.stream.StreamInput;
import com.naqqa.elasticsearch.common.io.stream.StreamOutput;
import com.naqqa.elasticsearch.common.io.stream.Writeable;
import com.naqqa.elasticsearch.transport.Connection;
import com.naqqa.elasticsearch.transport.RemoteTransportException;
import com.naqqa.elasticsearch.transport.TransportChannel;
import com.naqqa.elasticsearch.transport.TransportException;
import com.naqqa.elasticsearch.transport.TransportRequest;
import com.naqqa.elasticsearch.transport.TransportRequestOptions;
import com.naqqa.elasticsearch.transport.TransportResponse;
import com.naqqa.elasticsearch.transport.TransportResponseHandler;
import com.naqqa.elasticsearch.transport.TransportService;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class Wire {

    private Wire() {
    }

    public record Bytes(byte[] data) implements TransportRequest, TransportResponse {

        public Bytes(StreamInput in) throws IOException {
            this(in.readByteArray());
        }

        @Override
        public void writeTo(StreamOutput out) throws IOException {
            out.writeByteArray(data);
        }
    }

    public interface AsyncHandler {
        CompletableFuture<byte[]> handle(byte[] request) throws Exception;
    }

    public static byte[] encode(Map<String, Object> value) {
        try {
            BytesStreamOutput out = new BytesStreamOutput(256);
            out.writeGenericValue(value);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> decode(byte[] bytes) {
        try {
            Object value = new ByteBufferStreamInput(bytes).readGenericValue();
            Map<String, Object> out = new LinkedHashMap<>();
            if (value instanceof Map<?, ?> m) {
                for (Map.Entry<?, ?> e : m.entrySet()) {
                    out.put(String.valueOf(e.getKey()), e.getValue());
                }
            }
            return out;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static byte[] serialize(com.naqqa.elasticsearch.cluster.io.Writeable writeable) {
        try {
            return com.naqqa.elasticsearch.cluster.io.StreamUtils.serialize(writeable);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static void register(TransportService transportService, String action, AsyncHandler handler) {
        transportService.registerRequestHandler(action, Bytes::new, (request, channel) -> {
            CompletableFuture<byte[]> future;
            try {
                future = handler.handle(request.data());
            } catch (Exception e) {
                future = CompletableFuture.failedFuture(e);
            }
            future.whenComplete((bytes, error) -> respond(channel, bytes, error));
        });
    }

    private static void respond(TransportChannel channel, byte[] bytes, Throwable error) {
        try {
            if (error != null) {
                Throwable cause = unwrap(error);
                channel.sendResponse(cause instanceof Exception ex ? ex : new RuntimeException(cause));
            } else {
                channel.sendResponse(new Bytes(bytes == null ? new byte[0] : bytes));
            }
        } catch (IOException | RuntimeException ignored) {
        }
    }

    public static Throwable unwrap(Throwable t) {
        Throwable current = t;
        while ((current instanceof java.util.concurrent.CompletionException || current instanceof ExecutionException)
            && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    public static CompletableFuture<byte[]> send(TransportService transportService, Connection connection, String action,
                                                 byte[] payload, long timeoutMillis) {
        CompletableFuture<byte[]> future = new CompletableFuture<>();
        try {
            transportService.sendRequest(connection, action, new Bytes(payload), TransportRequestOptions.of().withTimeout(timeoutMillis),
                new TransportResponseHandler<Bytes>() {
                    @Override
                    public void handleResponse(Bytes response) {
                        future.complete(response.data());
                    }

                    @Override
                    public void handleException(TransportException exp) {
                        future.completeExceptionally(exp);
                    }

                    @Override
                    public Writeable.Reader<Bytes> reader() {
                        return Bytes::new;
                    }
                });
        } catch (Exception e) {
            future.completeExceptionally(e);
        }
        return future;
    }

    public static byte[] sendSync(TransportService transportService, Connection connection, String action, byte[] payload,
                                  long timeoutMillis) throws IOException {
        try {
            return send(transportService, connection, action, payload, timeoutMillis).get(timeoutMillis + 1_000L, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while waiting for [" + action + "]", e);
        } catch (ExecutionException e) {
            Throwable cause = unwrap(e);
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            throw new IOException("[" + action + "] failed: " + cause.getMessage(), cause);
        } catch (TimeoutException e) {
            throw new IOException("[" + action + "] timed out after [" + timeoutMillis + "ms]", e);
        }
    }

    public static String remoteClass(Throwable t) {
        Throwable cause = unwrap(t);
        return cause instanceof RemoteTransportException rte ? rte.remoteExceptionClassName() : cause.getClass().getName();
    }
}
