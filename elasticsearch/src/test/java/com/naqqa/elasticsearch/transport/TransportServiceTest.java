package com.naqqa.elasticsearch.transport;

import com.naqqa.elasticsearch.common.io.stream.StreamInput;
import com.naqqa.elasticsearch.common.io.stream.StreamOutput;
import com.naqqa.elasticsearch.common.threadpool.ThreadPool;
import com.naqqa.elasticsearch.test.Assert;
import com.naqqa.elasticsearch.test.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public class TransportServiceTest {

    static final class EchoRequest implements TransportRequest {
        final String value;

        EchoRequest(String value) {
            this.value = value;
        }

        EchoRequest(StreamInput in) throws IOException {
            this.value = in.readString();
        }

        @Override
        public void writeTo(StreamOutput out) throws IOException {
            out.writeString(value);
        }
    }

    static final class EchoResponse implements TransportResponse {
        final String value;

        EchoResponse(String value) {
            this.value = value;
        }

        EchoResponse(StreamInput in) throws IOException {
            this.value = in.readString();
        }

        @Override
        public void writeTo(StreamOutput out) throws IOException {
            out.writeString(value);
        }
    }

    private static final class Env {
        final ThreadPool poolA = new ThreadPool();
        final ThreadPool poolB = new ThreadPool();
        final TransportService serviceA;
        final TransportService serviceB;

        Env() {
            this(TransportSettings.defaults());
        }

        Env(TransportSettings settings) {
            serviceA = new TransportService("A", new InetSocketAddress("127.0.0.1", 0), poolA, settings);
            serviceB = new TransportService("B", new InetSocketAddress("127.0.0.1", 0), poolB, settings);
            serviceA.start();
            serviceB.start();
        }

        void registerEcho() {
            serviceB.registerRequestHandler(
                "internal:test/echo",
                EchoRequest::new,
                (request, channel) -> channel.sendResponse(new EchoResponse("echo:" + request.value))
            );
        }

        void close() {
            serviceA.close();
            serviceB.close();
            poolA.close();
            poolB.close();
        }
    }

    @Test
    public void testRequestResponseRoundTrip() throws Exception {
        Env env = new Env();
        try {
            env.registerEcho();
            Connection connection = env.serviceA.connectToNode(env.serviceB.localNode(), ConnectionProfile.buildDefault());
            CompletableFuture<String> future = new CompletableFuture<>();
            env.serviceA.sendRequest(connection, "internal:test/echo", new EchoRequest("hello"), new TransportResponseHandler<EchoResponse>() {
                @Override
                public void handleResponse(EchoResponse response) {
                    future.complete(response.value);
                }

                @Override
                public void handleException(TransportException exp) {
                    future.completeExceptionally(exp);
                }

                @Override
                public com.naqqa.elasticsearch.common.io.stream.Writeable.Reader<EchoResponse> reader() {
                    return EchoResponse::new;
                }
            });
            Assert.assertEquals("echo:hello", future.get(10, TimeUnit.SECONDS));
            connection.close();
        } finally {
            env.close();
        }
    }

    @Test
    public void testConcurrentRequestsCorrelatedCorrectly() throws Exception {
        Env env = new Env();
        try {
            env.registerEcho();
            Connection connection = env.serviceA.connectToNode(env.serviceB.localNode(), ConnectionProfile.buildDefault());
            int count = 100;
            List<CompletableFuture<String>> futures = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                CompletableFuture<String> future = new CompletableFuture<>();
                futures.add(future);
                String payload = "req-" + i;
                env.serviceA.sendRequest(connection, "internal:test/echo", new EchoRequest(payload), new TransportResponseHandler<EchoResponse>() {
                    @Override
                    public void handleResponse(EchoResponse response) {
                        future.complete(response.value);
                    }

                    @Override
                    public void handleException(TransportException exp) {
                        future.completeExceptionally(exp);
                    }

                    @Override
                    public com.naqqa.elasticsearch.common.io.stream.Writeable.Reader<EchoResponse> reader() {
                        return EchoResponse::new;
                    }
                });
            }
            for (int i = 0; i < count; i++) {
                Assert.assertEquals("echo:req-" + i, futures.get(i).get(10, TimeUnit.SECONDS));
            }
            connection.close();
        } finally {
            env.close();
        }
    }

    @Test
    public void testTimeoutFiresWithoutHandlerResponse() throws Exception {
        Env env = new Env();
        try {
            env.serviceB.registerRequestHandler("internal:test/blackhole", EchoRequest::new, (request, channel) -> {
            });
            Connection connection = env.serviceA.connectToNode(env.serviceB.localNode(), ConnectionProfile.buildDefault());
            CompletableFuture<TransportException> future = new CompletableFuture<>();
            env.serviceA.sendRequest(
                connection,
                "internal:test/blackhole",
                new EchoRequest("nope"),
                TransportRequestOptions.of().withTimeout(300),
                new TransportResponseHandler<EchoResponse>() {
                    @Override
                    public void handleResponse(EchoResponse response) {
                        future.completeExceptionally(new AssertionError("unexpected response"));
                    }

                    @Override
                    public void handleException(TransportException exp) {
                        future.complete(exp);
                    }

                    @Override
                    public com.naqqa.elasticsearch.common.io.stream.Writeable.Reader<EchoResponse> reader() {
                        return EchoResponse::new;
                    }
                }
            );
            TransportException exception = future.get(5, TimeUnit.SECONDS);
            Assert.assertTrue(exception instanceof ReceiveTimeoutTransportException, "expected ReceiveTimeoutTransportException but got " + exception);
            connection.close();
        } finally {
            env.close();
        }
    }

    @Test
    public void testCompressedPayloadRoundTrip() throws Exception {
        Env env = new Env();
        try {
            env.registerEcho();
            Connection connection = env.serviceA.connectToNode(env.serviceB.localNode(), ConnectionProfile.buildDefault());
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 2000; i++) {
                sb.append("payload-data-");
            }
            String big = sb.toString();
            CompletableFuture<String> future = new CompletableFuture<>();
            env.serviceA.sendRequest(
                connection,
                "internal:test/echo",
                new EchoRequest(big),
                TransportRequestOptions.of().withCompress(true),
                new TransportResponseHandler<EchoResponse>() {
                    @Override
                    public void handleResponse(EchoResponse response) {
                        future.complete(response.value);
                    }

                    @Override
                    public void handleException(TransportException exp) {
                        future.completeExceptionally(exp);
                    }

                    @Override
                    public com.naqqa.elasticsearch.common.io.stream.Writeable.Reader<EchoResponse> reader() {
                        return EchoResponse::new;
                    }
                }
            );
            Assert.assertEquals("echo:" + big, future.get(10, TimeUnit.SECONDS));
            connection.close();
        } finally {
            env.close();
        }
    }

    @Test
    public void testKeepAlivePingPong() throws Exception {
        Env env = new Env(new TransportSettings(5_000L, 5_000L, 250L, 2_000L));
        try {
            Connection connection = env.serviceA.connectToNode(env.serviceB.localNode(), ConnectionProfile.buildDefault());
            Thread.sleep(900);
            Assert.assertTrue(env.serviceB.pingsReceived() >= 1, "expected at least one keepalive ping to be received");
            Assert.assertTrue(connection.isOpen());
            connection.close();
        } finally {
            env.close();
        }
    }

    @Test
    public void testConnectionProfileOpensCorrectChannelCounts() throws Exception {
        Env env = new Env();
        try {
            ConnectionProfile profile = ConnectionProfile.builder()
                .addConnections(ConnectionProfile.ChannelType.REG, 2)
                .addConnections(ConnectionProfile.ChannelType.BULK, 3)
                .addConnections(ConnectionProfile.ChannelType.STATE, 1)
                .build();
            Connection connection = env.serviceA.connectToNode(env.serviceB.localNode(), profile);
            NodeChannels nodeChannels = (NodeChannels) connection;
            Map<ConnectionProfile.ChannelType, Integer> counts = nodeChannels.channelCounts();
            Assert.assertEquals(Integer.valueOf(2), counts.get(ConnectionProfile.ChannelType.REG));
            Assert.assertEquals(Integer.valueOf(3), counts.get(ConnectionProfile.ChannelType.BULK));
            Assert.assertEquals(Integer.valueOf(1), counts.get(ConnectionProfile.ChannelType.STATE));
            Assert.assertEquals(6, nodeChannels.allChannels().size());
            connection.close();
        } finally {
            env.close();
        }
    }
}
