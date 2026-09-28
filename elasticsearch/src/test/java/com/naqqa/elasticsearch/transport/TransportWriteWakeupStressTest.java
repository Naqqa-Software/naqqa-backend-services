package com.naqqa.elasticsearch.transport;

import com.naqqa.elasticsearch.common.io.stream.StreamInput;
import com.naqqa.elasticsearch.common.io.stream.StreamOutput;
import com.naqqa.elasticsearch.common.io.stream.Writeable;
import com.naqqa.elasticsearch.common.threadpool.ThreadPool;
import com.naqqa.elasticsearch.test.Assert;
import com.naqqa.elasticsearch.test.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public class TransportWriteWakeupStressTest {

    static final class Msg implements TransportRequest, TransportResponse {
        final int value;

        Msg(int value) {
            this.value = value;
        }

        Msg(StreamInput in) throws IOException {
            this.value = in.readVInt();
        }

        @Override
        public void writeTo(StreamOutput out) throws IOException {
            out.writeVInt(value);
        }
    }

    private static CompletableFuture<Integer> send(TransportService service, Connection connection, int value) {
        CompletableFuture<Integer> future = new CompletableFuture<>();
        service.sendRequest(connection, "internal:test/wakeup", new Msg(value),
            TransportRequestOptions.of().withTimeout(60_000L), new TransportResponseHandler<Msg>() {
                @Override
                public void handleResponse(Msg response) {
                    future.complete(response.value);
                }

                @Override
                public void handleException(TransportException exp) {
                    future.completeExceptionally(exp);
                }

                @Override
                public Writeable.Reader<Msg> reader() {
                    return Msg::new;
                }
            });
        return future;
    }

    @Test
    public void burstsOfConcurrentSmallFramesAreNeverStrandedInTheWriteQueue() throws Exception {
        ThreadPool poolA = new ThreadPool();
        ThreadPool poolB = new ThreadPool();
        TransportService a = new TransportService("A", new InetSocketAddress("127.0.0.1", 0), poolA);
        TransportService b = new TransportService("B", new InetSocketAddress("127.0.0.1", 0), poolB);
        a.start();
        b.start();
        int threads = 6;
        ExecutorService senders = Executors.newFixedThreadPool(threads);
        try {
            b.registerRequestHandler("internal:test/wakeup", Msg::new, (request, channel) -> channel.sendResponse(new Msg(request.value + 1)));
            ConnectionProfile profile = ConnectionProfile.builder().addConnections(ConnectionProfile.ChannelType.REG, 1).build();
            Connection connection = a.connectToNode(b.localNode(), profile);
            CyclicBarrier barrier = new CyclicBarrier(threads);
            int waves = 6000;
            for (int wave = 0; wave < waves; wave++) {
                List<Future<CompletableFuture<Integer>>> sent = new ArrayList<>();
                for (int t = 0; t < threads; t++) {
                    int value = wave * threads + t;
                    sent.add(senders.submit(() -> {
                        barrier.await(10, TimeUnit.SECONDS);
                        return send(a, connection, value);
                    }));
                }
                for (int t = 0; t < threads; t++) {
                    CompletableFuture<Integer> response = sent.get(t).get(10, TimeUnit.SECONDS);
                    int expected = wave * threads + t + 1;
                    try {
                        Assert.assertEquals(Integer.valueOf(expected), response.get(3, TimeUnit.SECONDS));
                    } catch (java.util.concurrent.TimeoutException e) {
                        throw new AssertionError("response for request " + (expected - 1) + " in wave " + wave
                            + " was stranded in a write queue", e);
                    }
                }
            }
            connection.close();
        } finally {
            senders.shutdownNow();
            a.close();
            b.close();
            poolA.close();
            poolB.close();
        }
    }
}
