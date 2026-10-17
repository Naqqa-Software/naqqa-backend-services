package com.naqqa.auth.support;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

public class BackchannelReceiver {

    public record Delivery(String path, Map<String, String> form) {
    }

    private final HttpServer server;
    private final LinkedBlockingQueue<Delivery> deliveries = new LinkedBlockingQueue<>();

    private BackchannelReceiver() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        server.createContext("/bc", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            deliveries.add(new Delivery(ex.getRequestURI().getPath(), FakeIdp.form(body)));
            ex.sendResponseHeaders(200, -1);
            ex.close();
        });
        server.start();
    }

    public static BackchannelReceiver start() {
        return new BackchannelReceiver();
    }

    public String url(String path) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/bc/" + path;
    }

    public void clear() {
        deliveries.clear();
    }

    public List<Delivery> await(int count, Duration timeout) {
        List<Delivery> out = new ArrayList<>();
        long end = System.nanoTime() + timeout.toNanos();
        while (out.size() < count) {
            long left = end - System.nanoTime();
            if (left <= 0) {
                break;
            }
            try {
                Delivery d = deliveries.poll(left, TimeUnit.NANOSECONDS);
                if (d != null) {
                    out.add(d);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return out;
    }
}
